// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package com.intellij.openapi.vcs;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.command.CommandEvent;
import com.intellij.openapi.command.CommandListener;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.actions.VcsContextFactory;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.VcsIgnoreManager;
import com.intellij.openapi.vcs.changes.ignore.IgnoreFilesProcessorImpl;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.vfs.newvfs.NewVirtualFile;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.util.SmartList;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.vcsUtil.VcsUtil;
import kotlin.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;

import static java.util.Collections.emptyMap;

public abstract class VcsVFSListener implements Disposable {
  protected static final Logger LOG = Logger.getInstance(VcsVFSListener.class);

  private final ProjectLevelVcsManager myVcsManager;
  private final VcsIgnoreManager myVcsIgnoreManager;
  private final VcsFileListenerContextHelper myVcsFileListenerContextHelper;

  protected static class MovedFileInfo {
    @NotNull
    public final String myOldPath;
    @NotNull
    public String myNewPath;
    @NotNull
    private final VirtualFile myFile;

    MovedFileInfo(@NotNull VirtualFile file, @NotNull String newPath) {
      myOldPath = file.getPath();
      myNewPath = newPath;
      myFile = file;
    }

    @Override
    public String toString() {
      return String.format("MovedFileInfo{[%s] -> [%s]}", myOldPath, myNewPath);
    }
  }

  protected final Project myProject;
  protected final AbstractVcs myVcs;
  protected final ChangeListManager myChangeListManager;
  private final VcsShowConfirmationOption myAddOption;
  protected final VcsShowConfirmationOption myRemoveOption;
  protected final List<VirtualFile> myAddedFiles = new ArrayList<>();
  private final Map<VirtualFile, VirtualFile> myCopyFromMap = new HashMap<>();
  protected final List<VcsException> myExceptions = new SmartList<>();
  protected final List<FilePath> myDeletedFiles = new ArrayList<>();
  protected final List<FilePath> myDeletedWithoutConfirmFiles = new ArrayList<>();
  protected final List<MovedFileInfo> myMovedFiles = new ArrayList<>();
  private final ProjectConfigurationFilesProcessorImpl myProjectConfigurationFilesProcessor;
  protected final ExternallyAddedFilesProcessorImpl myExternalFilesProcessor;

  protected enum VcsDeleteType {SILENT, CONFIRM, IGNORE}

  /**
   * @see #installListeners()
   */
  protected VcsVFSListener(@NotNull AbstractVcs vcs) {
    myProject = vcs.getProject();
    myVcs = vcs;
    myChangeListManager = ChangeListManager.getInstance(myProject);
    myVcsIgnoreManager = VcsIgnoreManager.getInstance(myProject);

    myVcsManager = ProjectLevelVcsManager.getInstance(myProject);
    myAddOption = myVcsManager.getStandardConfirmation(VcsConfiguration.StandardConfirmation.ADD, vcs);
    myRemoveOption = myVcsManager.getStandardConfirmation(VcsConfiguration.StandardConfirmation.REMOVE, vcs);

    myVcsFileListenerContextHelper = VcsFileListenerContextHelper.getInstance(myProject);

    myProjectConfigurationFilesProcessor = createProjectConfigurationFilesProcessor();
    myExternalFilesProcessor = createExternalFilesProcessor();
  }

  /**
   * @deprecated Use {@link #VcsVFSListener(AbstractVcs)} followed by {@link #installListeners()}
   */
  @Deprecated
  protected VcsVFSListener(@NotNull Project project, @NotNull AbstractVcs vcs) {
    this(vcs);
    installListeners();
  }

  protected void installListeners() {
    VirtualFileManager.getInstance().addVirtualFileListener(new MyVirtualFileListener(), this);
    VirtualFileManager.getInstance().addAsyncFileListener(new MyAsyncVfsListener(), this);
    myProject.getMessageBus().connect(this).subscribe(CommandListener.TOPIC, new MyCommandAdapter());

    myProjectConfigurationFilesProcessor.install();
    myExternalFilesProcessor.install();
    new IgnoreFilesProcessorImpl(myProject, myVcs, this).install();
  }

  @Override
  public void dispose() {
  }

  protected boolean isEventIgnored(@NotNull VirtualFileEvent event) {
    if (event.isFromRefresh()) return true;
    return !isUnderMyVcs(event.getFile());
  }

  private boolean isUnderMyVcs(@NotNull VirtualFile file) {
    return myVcsManager.getVcsFor(file) == myVcs &&
           myVcsManager.isFileInContent(file) &&
           !myVcsIgnoreManager.isPotentiallyIgnoredFile(file);
  }

  protected void executeAdd() {
    final List<VirtualFile> addedFiles = acquireAddedFiles();
    LOG.debug("executeAdd. addedFiles: ", addedFiles);
    addedFiles.removeIf(myVcsFileListenerContextHelper::isAdditionIgnored);
    final Map<VirtualFile, VirtualFile> copyFromMap = acquireCopiedFiles();
    if (! addedFiles.isEmpty()) {
      executeAdd(addedFiles, copyFromMap);
    }
  }

  /**
   * @return get map of copied files and clear the map
   */
  @NotNull
  private Map<VirtualFile, VirtualFile> acquireCopiedFiles() {
    final Map<VirtualFile, VirtualFile> copyFromMap = new HashMap<>(myCopyFromMap);
    myCopyFromMap.clear();
    return copyFromMap;
  }

  /**
   * @return get list of added files and clear previous list
   */
  @NotNull
  private List<VirtualFile> acquireAddedFiles() {
    final List<VirtualFile> addedFiles = new ArrayList<>(myAddedFiles);
    myAddedFiles.clear();
    return addedFiles;
  }

  /**
   * Execute add that performs adding from specific collections
   *
   * @param addedFiles  the added files
   * @param copyFromMap the copied files
   */
  protected void executeAdd(@NotNull List<VirtualFile> addedFiles, @NotNull Map<VirtualFile, VirtualFile> copyFromMap) {
    LOG.debug("executeAdd. add-option: ", myAddOption.getValue(), ", files to add: ", addedFiles);
    if (myAddOption.getValue() == VcsShowConfirmationOption.Value.DO_NOTHING_SILENTLY) return;

    addedFiles = myProjectConfigurationFilesProcessor.processFiles(addedFiles);

    if (myAddOption.getValue() == VcsShowConfirmationOption.Value.DO_ACTION_SILENTLY) {
      performAdding(addedFiles, copyFromMap);
    }
    else {
      final AbstractVcsHelper helper = AbstractVcsHelper.getInstance(myProject);
      // TODO[yole]: nice and clean description label
      Collection<VirtualFile> filesToProcess = helper.selectFilesToProcess(addedFiles, getAddTitle(), null,
                                                                           getSingleFileAddTitle(), getSingleFileAddPromptTemplate(),
                                                                           myAddOption);
      if (filesToProcess != null) {
        performAdding(new ArrayList<>(filesToProcess), copyFromMap);
      }
    }
  }

  protected void executeAddWithoutIgnores(@NotNull List<VirtualFile> addedFiles,
                                          @NotNull Map<VirtualFile, VirtualFile> copyFromMap,
                                          @NotNull ExecuteAddCallback executeAddCallback) {
    executeAddCallback.executeAdd(addedFiles, copyFromMap);
  }

  @FunctionalInterface
  protected interface ExecuteAddCallback {
    void executeAdd(@NotNull List<VirtualFile> addedFiles, @NotNull Map<VirtualFile, VirtualFile> copyFromMap);
  }

  protected void saveUnsavedVcsIgnoreFiles() {
    FileDocumentManager fileDocumentManager = FileDocumentManager.getInstance();
    Set<String> ignoreFileNames = VcsUtil.getVcsIgnoreFileNames(myProject);

    for (Document document : fileDocumentManager.getUnsavedDocuments()) {
      VirtualFile file = fileDocumentManager.getFile(document);
      if (file != null && ignoreFileNames.contains(file.getName())) {
        fileDocumentManager.saveDocument(document);
      }
    }
  }

  private void addFileToDelete(@NotNull VirtualFile file) {
    if (file.isDirectory() && file instanceof NewVirtualFile && !isDirectoryVersioningSupported()) {
      for (VirtualFile child : ((NewVirtualFile)file).getCachedChildren()) {
        addFileToDelete(child);
      }
    }
    else {
      final VcsDeleteType type = needConfirmDeletion(file);
      final FilePath filePath =
        VcsContextFactory.SERVICE.getInstance().createFilePathOn(new File(file.getPath()), file.isDirectory());
      if (type == VcsDeleteType.CONFIRM) {
        myDeletedFiles.add(filePath);
      }
      else if (type == VcsDeleteType.SILENT) {
        myDeletedWithoutConfirmFiles.add(filePath);
      }
    }
  }

  protected void executeDelete() {
    final List<FilePath> filesToDelete = new ArrayList<>(myDeletedWithoutConfirmFiles);
    final List<FilePath> deletedFiles = new ArrayList<>(myDeletedFiles);
    myDeletedWithoutConfirmFiles.clear();
    myDeletedFiles.clear();

    filesToDelete.removeIf(myVcsFileListenerContextHelper::isDeletionIgnored);
    deletedFiles.removeIf(myVcsFileListenerContextHelper::isDeletionIgnored);

    if (deletedFiles.isEmpty() &&filesToDelete.isEmpty()) return;

    if (myRemoveOption.getValue() != VcsShowConfirmationOption.Value.DO_NOTHING_SILENTLY) {
      if (myRemoveOption.getValue() == VcsShowConfirmationOption.Value.DO_ACTION_SILENTLY || deletedFiles.isEmpty()) {
        filesToDelete.addAll(deletedFiles);
      }
      else {
        Collection<FilePath> filePaths = selectFilePathsToDelete(deletedFiles);
        if (filePaths != null) {
          filesToDelete.addAll(filePaths);
        }
      }
    }
    performDeletion(filesToDelete);
  }

  /**
   * Select file paths to delete
   *
   * @param deletedFiles deleted files set
   * @return selected files or null (that is considered as empty file set)
   */
  @Nullable
  protected Collection<FilePath> selectFilePathsToDelete(@NotNull List<FilePath> deletedFiles) {
    AbstractVcsHelper helper = AbstractVcsHelper.getInstance(myProject);
    return helper.selectFilePathsToProcess(deletedFiles, getDeleteTitle(), null, getSingleFileDeleteTitle(),
                                           getSingleFileDeletePromptTemplate(), myRemoveOption);
  }

  protected void beforeContentsChange(@NotNull VirtualFileEvent event, @NotNull VirtualFile file) {
  }

  protected void fileAdded(@NotNull VirtualFileEvent event, @NotNull VirtualFile file) {
    if (!isEventIgnored(event) &&
        (isDirectoryVersioningSupported() || !file.isDirectory())) {
      LOG.debug("Adding [", file, "] to added files");
      myAddedFiles.add(file);
    }
  }

  private void addFileToMove(@NotNull VirtualFile file, @NotNull String newParentPath, @NotNull String newName) {
    if (file.isDirectory() && !file.is(VFileProperty.SYMLINK) && !isDirectoryVersioningSupported()) {
      @SuppressWarnings("UnsafeVfsRecursion") VirtualFile[] children = file.getChildren();
      if (children != null) {
        for (VirtualFile child : children) {
          addFileToMove(child, newParentPath + "/" + newName, child.getName());
        }
      }
    }
    else {
      processMovedFile(file, newParentPath, newName);
    }
  }

  protected boolean filterOutUnknownFiles() {
    return true;
  }

  protected void processMovedFile(@NotNull VirtualFile file, @NotNull String newParentPath, @NotNull String newName) {
    final FileStatus status = ChangeListManager.getInstance(myProject).getStatus(file);
    LOG.debug("Checking moved file ", file, "; status=", status);

    String newPath = newParentPath + "/" + newName;
    if (!(filterOutUnknownFiles() && status == FileStatus.UNKNOWN) && status != FileStatus.IGNORED) {
      MovedFileInfo existingMovedFile = ContainerUtil.find(myMovedFiles, info -> Comparing.equal(info.myFile, file));
      if (existingMovedFile != null) {
        LOG.debug("Reusing existing moved file [" + file + "] with new path [" + newPath + "]");
        existingMovedFile.myNewPath = newPath;
      }
      else {
        LOG.debug("Registered moved file ", file);
        myMovedFiles.add(new MovedFileInfo(file, newPath));
      }
    }
    else {
      // If a file is moved on top of another file (overwrite), the VFS at first removes the original file,
      // and then performs the "clean" move.
      // But we don't need to handle this deletion by the VCS: it is not a real deletion, but just a trick to implement the overwrite.
      // This situation is already handled in doNotDeleteAddedCopiedOrMovedFiles(), but that method is called at the end of the command,
      // so it is not suitable for moving unversioned files: if an unversioned file is moved, it won't be recorded,
      // won't affect doNotDeleteAddedCopiedOrMovedFiles(), and therefore won't save the file from deletion.
      // Thus here goes a special handle for unversioned files overwrite-move.
      myDeletedFiles.remove(VcsUtil.getFilePath(newPath));
    }
  }

  private void executeMoveRename() {
    final List<MovedFileInfo> movedFiles = new ArrayList<>(myMovedFiles);
    LOG.debug("executeMoveRename " + movedFiles);
    myMovedFiles.clear();
    performMoveRename(movedFiles);
  }

  @NotNull
  protected VcsDeleteType needConfirmDeletion(@NotNull VirtualFile file) {
    return VcsDeleteType.CONFIRM;
  }

  @NotNull
  protected abstract String getAddTitle();

  @NotNull
  protected abstract String getSingleFileAddTitle();

  @NotNull
  protected abstract String getSingleFileAddPromptTemplate();

  protected abstract void performAdding(@NotNull Collection<VirtualFile> addedFiles, @NotNull Map<VirtualFile, VirtualFile> copyFromMap);

  @NotNull
  protected abstract String getDeleteTitle();

  protected abstract String getSingleFileDeleteTitle();

  protected abstract String getSingleFileDeletePromptTemplate();

  protected abstract void performDeletion(@NotNull List<FilePath> filesToDelete);

  protected abstract void performMoveRename(@NotNull List<MovedFileInfo> movedFiles);

  protected abstract boolean isDirectoryVersioningSupported();

  @SuppressWarnings("unchecked")
  private ExternallyAddedFilesProcessorImpl createExternalFilesProcessor() {
    return new ExternallyAddedFilesProcessorImpl(myProject,
                                                 this,
                                                 myVcs,
                                                 (files) -> {
                                                   performAdding((Collection<VirtualFile>)files, emptyMap());
                                                   return Unit.INSTANCE;
                                                 });
  }

  @SuppressWarnings("unchecked")
  private ProjectConfigurationFilesProcessorImpl createProjectConfigurationFilesProcessor() {
    return new ProjectConfigurationFilesProcessorImpl(myProject,
                                                      this,
                                                      myVcs.getDisplayName(),
                                                      (files) -> {
                                                        performAdding((Collection<VirtualFile>)files, emptyMap());
                                                        return Unit.INSTANCE;
                                                      });
  }

  private class MyVirtualFileListener implements VirtualFileListener {
    @Override
    public void fileCreated(@NotNull final VirtualFileEvent event) {
      VirtualFile file = event.getFile();
      LOG.debug("fileCreated: ", file);
      fileAdded(event, file);
    }

    @Override
    public void fileCopied(@NotNull final VirtualFileCopyEvent event) {
      if (isEventIgnored(event) || myChangeListManager.isIgnoredFile(event.getFile())) return;
      final AbstractVcs oldVcs = ProjectLevelVcsManager.getInstance(myProject).getVcsFor(event.getOriginalFile());
      if (oldVcs == myVcs) {
        final VirtualFile parent = event.getFile().getParent();
        if (parent != null) {
          myAddedFiles.add(event.getFile());
          myCopyFromMap.put(event.getFile(), event.getOriginalFile());
        }
      }
      else {
        myAddedFiles.add(event.getFile());
      }
    }

    @Override
    public void beforeFileDeletion(@NotNull VirtualFileEvent event) {
      VirtualFile file = event.getFile();
      if (isEventIgnored(event)) {
        return;
      }
      // files are checked for being ignored, directories are handled recursively
      if (!file.isDirectory()) {
        if (!myChangeListManager.isIgnoredFile(file)) {
          addFileToDelete(file);
        }
      }
      else {
        final List<VirtualFile> list = new ArrayList<>();
        VcsUtil.collectFiles(file, list, true, isDirectoryVersioningSupported());
        for (VirtualFile child : list) {
          if (!myChangeListManager.isIgnoredFile(child)) {
            addFileToDelete(child);
          }
        }
      }
    }

    @Override
    public void beforeFileMovement(@NotNull final VirtualFileMoveEvent event) {
      if (isEventIgnored(event)) return;
      final VirtualFile file = event.getFile();
      final AbstractVcs newVcs = ProjectLevelVcsManager.getInstance(myProject).getVcsFor(event.getNewParent());
      LOG.debug("beforeFileMovement ", event, " into ", newVcs);
      if (newVcs == myVcs) {
        addFileToMove(file, event.getNewParent().getPath(), file.getName());
      }
      else {
        addFileToDelete(event.getFile());
      }
    }

    @Override
    public void fileMoved(@NotNull final VirtualFileMoveEvent event) {
      if (isEventIgnored(event)) return;
      final AbstractVcs oldVcs = ProjectLevelVcsManager.getInstance(myProject).getVcsFor(event.getOldParent());
      if (oldVcs != myVcs) {
        myAddedFiles.add(event.getFile());
      }
    }

    @Override
    public void beforePropertyChange(@NotNull final VirtualFilePropertyEvent event) {
      if (!isEventIgnored(event) && event.isRename()) {
        LOG.debug("before file rename ", event);
        String newName = (String)event.getNewValue();
        VirtualFile file = event.getFile();
        VirtualFile parent = file.getParent();
        if (parent != null) {
          addFileToMove(file, parent.getPath(), newName);
        }
      }
    }

  }

  private class MyAsyncVfsListener implements AsyncFileListener {
    @Nullable
    @Override
    public ChangeApplier prepareChange(@NotNull List<? extends VFileEvent> events) {
      List<VirtualFileEvent> filtered = new ArrayList<>();
      for (VFileEvent event : events) {
        if (event instanceof VFileContentChangeEvent) {
          ProgressManager.checkCanceled();
          VirtualFile file = Objects.requireNonNull(event.getFile());
          if (isUnderMyVcs(file)) {
            VFileContentChangeEvent ce = (VFileContentChangeEvent)event;
            filtered.add(
              new VirtualFileEvent(event.getRequestor(), file, file.getParent(), ce.getOldModificationStamp(), ce.getModificationStamp()));
          }
        }
      }
      return filtered.isEmpty() ? null : new ChangeApplier() {
        @Override
        public void beforeVfsChange() {
          for (VirtualFileEvent event : filtered) {
            beforeContentsChange(event, event.getFile());
          }
        }
      };
    }
  }

  private class MyCommandAdapter implements CommandListener {

    private void checkMovedAddedSourceBack() {
      if (myAddedFiles.isEmpty() || myMovedFiles.isEmpty()) return;

      final Map<String, VirtualFile> addedPaths = new HashMap<>(myAddedFiles.size());
      for (VirtualFile file : myAddedFiles) {
        addedPaths.put(file.getPath(), file);
      }

      for (Iterator<MovedFileInfo> iterator = myMovedFiles.iterator(); iterator.hasNext();) {
        final MovedFileInfo movedFile = iterator.next();
        if (addedPaths.containsKey(movedFile.myOldPath)) {
          iterator.remove();
          final VirtualFile oldAdded = addedPaths.get(movedFile.myOldPath);
          myAddedFiles.remove(oldAdded);
          myAddedFiles.add(movedFile.myFile);
          myCopyFromMap.put(oldAdded, movedFile.myFile);
        }
      }
    }

    // If a file is scheduled for deletion, and at the same time for copying or addition, don't delete it.
    // It happens during Overwrite command or undo of overwrite.
    private void doNotDeleteAddedCopiedOrMovedFiles() {
      Collection<String> copiedAddedMoved = new ArrayList<>();
      for (VirtualFile file : myCopyFromMap.keySet()) {
        copiedAddedMoved.add(file.getPath());
      }
      for (VirtualFile file : myAddedFiles) {
        copiedAddedMoved.add(file.getPath());
      }
      for (MovedFileInfo movedFileInfo : myMovedFiles) {
        copiedAddedMoved.add(movedFileInfo.myNewPath);
      }

      myDeletedFiles.removeIf(path -> copiedAddedMoved.contains(FileUtil.toSystemIndependentName(path.getPath())));
      myDeletedWithoutConfirmFiles.removeIf(path -> copiedAddedMoved.contains(FileUtil.toSystemIndependentName(path.getPath())));
    }

    @Override
    public void commandFinished(@NotNull final CommandEvent event) {
      if (myProject != event.getProject()) return;
      if (!myAddedFiles.isEmpty() || !myDeletedFiles.isEmpty() || !myDeletedWithoutConfirmFiles.isEmpty() || !myMovedFiles.isEmpty()) {
        doNotDeleteAddedCopiedOrMovedFiles();
        checkMovedAddedSourceBack();
        if (!myAddedFiles.isEmpty()) {
          executeAdd();
          myAddedFiles.clear();
        }
        if (!myDeletedFiles.isEmpty() || !myDeletedWithoutConfirmFiles.isEmpty()) {
          executeDelete();
          myDeletedFiles.clear();
          myDeletedWithoutConfirmFiles.clear();
        }
        if (!myMovedFiles.isEmpty()) {
          executeMoveRename();
          myMovedFiles.clear();
        }
        if (! myExceptions.isEmpty()) {
          AbstractVcsHelper.getInstance(myProject).showErrors(myExceptions, myVcs.getDisplayName() + " operations errors");
        }
      }
    }

  }
}
