package com.intellij.structuralsearch.impl.matcher;

import com.intellij.dupLocator.iterators.ArrayBackedNodeIterator;
import com.intellij.dupLocator.iterators.NodeIterator;
import com.intellij.lang.Language;
import com.intellij.lang.StdLanguages;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypes;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ContentIterator;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageUtil;
import com.intellij.psi.search.DelegatingGlobalSearchScope;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.structuralsearch.*;
import com.intellij.structuralsearch.impl.matcher.compiler.PatternCompiler;
import com.intellij.structuralsearch.impl.matcher.handlers.MatchingHandler;
import com.intellij.structuralsearch.impl.matcher.handlers.TopLevelMatchingHandler;
import com.intellij.structuralsearch.impl.matcher.iterators.SsrFilteringNodeIterator;
import com.intellij.structuralsearch.impl.matcher.strategies.MatchingStrategy;
import com.intellij.structuralsearch.plugin.ui.Configuration;
import com.intellij.structuralsearch.plugin.util.CollectingMatchResultSink;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.PairProcessor;
import com.intellij.util.SmartList;
import com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * This class makes program structure tree matching:
 */
public class MatcherImpl {
  private static final Logger LOG = Logger.getInstance("#com.intellij.structuralsearch.impl.matcher.MatcherImpl");
  // project being worked on
  private Project project;

  // context of matching
  private MatchContext matchContext;
  private boolean isTesting;

  // visitor to delegate the real work
  private final GlobalMatchingVisitor visitor = new GlobalMatchingVisitor();
  private ProgressIndicator progress;
  private final TaskScheduler scheduler = new TaskScheduler();

  private int totalFilesToScan;
  private int scannedFilesCount;

  public MatcherImpl(final Project project, final MatchOptions matchOptions) {
    this.project = project;
    matchContext = new MatchContext();
    matchContext.setMatcher(visitor);

    if (matchOptions != null) {
      matchContext.setOptions(matchOptions);
      cacheCompiledPattern(matchOptions, PatternCompiler.compilePattern(project,matchOptions));
    }
  }

  static class LastMatchData {
    CompiledPattern lastPattern;
    MatchOptions lastOptions;
  }

  private static SoftReference<LastMatchData> lastMatchData;

  protected MatcherImpl(Project project) {
    this(project, null);
  }

  public static void validate(Project project, MatchOptions options) {
    PsiDocumentManager.getInstance(project).commitAllDocuments();
    CompiledPattern lastPattern;

    synchronized(MatcherImpl.class) {
      final LastMatchData data = new LastMatchData();
      lastPattern = data.lastPattern =  PatternCompiler.compilePattern(project,options);
      data.lastOptions = options;
      lastMatchData = new SoftReference<LastMatchData>(data);
    }

    class ValidatingVisitor extends JavaRecursiveElementWalkingVisitor {
      @Override public void visitAnnotation(PsiAnnotation annotation) {
        final PsiJavaCodeReferenceElement nameReferenceElement = annotation.getNameReferenceElement();

        if (nameReferenceElement == null ||
            !nameReferenceElement.getText().equals(MatchOptions.MODIFIER_ANNOTATION_NAME)) {
          return;
        }

        for(PsiNameValuePair pair:annotation.getParameterList().getAttributes()) {
          final PsiAnnotationMemberValue value = pair.getValue();

          if (value instanceof PsiArrayInitializerMemberValue) {
            for(PsiAnnotationMemberValue v:((PsiArrayInitializerMemberValue)value).getInitializers()) {
              final String name = StringUtil.stripQuotesAroundValue(v.getText());
              checkModifier(name);
            }

          } else {
            final String name = StringUtil.stripQuotesAroundValue(value.getText());
            checkModifier(name);
          }
        }
      }

      private void checkModifier(final String name) {
        if (!MatchOptions.INSTANCE_MODIFIER_NAME.equals(name) &&
            !MatchOptions.PACKAGE_LOCAL_MODIFIER_NAME.equals(name) &&
            Arrays.binarySearch(JavaMatchingVisitor.MODIFIERS, name) < 0
           ) {
          throw new MalformedPatternException(SSRBundle.message("invalid.modifier.type",name));
        }
      }
    }

    ValidatingVisitor visitor = new ValidatingVisitor();
    final NodeIterator nodes = lastPattern.getNodes();
    while(nodes.hasNext()) {
      nodes.current().accept( visitor );
      nodes.advance();
    }
    nodes.reset();
  }

  public static class CompiledOptions {
    public final List<Pair<MatchContext, Configuration>> matchContexts;

    public CompiledOptions(final List<Pair<MatchContext, Configuration>> matchContexts) {
      this.matchContexts = matchContexts;
    }

    public List<Pair<MatchContext, Configuration>> getMatchContexts() {
      return matchContexts;
    }
  }

  public static boolean checkIfShouldAttemptToMatch(MatchContext context,PsiElement elt) {
    CompiledPattern pattern = context.getPattern();
    PsiElement element = pattern.getNodes().current();
    MatchingHandler matchingHandler = pattern.getHandler(element);
    return matchingHandler != null && matchingHandler.canMatch(element, elt);
  }

  public void processMatchesInElement(MatchContext context, Configuration configuration,
                                      PsiElement element,
                                      PairProcessor<MatchResult, Configuration> processor) {
    configureOptions(context, configuration, element, processor);
    context.setShouldRecursivelyMatch(false);
    visitor.matchContext(new ArrayBackedNodeIterator(new PsiElement[] {element}));
  }

  public void clearContext() {
    matchContext.clear();
  }

  private void configureOptions(MatchContext context,
                                final Configuration configuration,
                                PsiElement psiFile,
                                final PairProcessor<MatchResult, Configuration> processor) {
    LocalSearchScope scope = new LocalSearchScope(psiFile);

    matchContext.clear();
    matchContext.setMatcher(visitor);

    MatchOptions options = context.getOptions();
    matchContext.setOptions(options);
    matchContext.setPattern(context.getPattern());
    matchContext.setShouldRecursivelyMatch(context.shouldRecursivelyMatch());
    visitor.setMatchContext(matchContext);

    matchContext.setSink(
      new MatchConstraintsSink(
        new MatchResultSink() {
          public void newMatch(MatchResult result) {
            processor.process(result, configuration);
          }

          public void processFile(PsiFile element) {
          }

          public void setMatchingProcess(MatchingProcess matchingProcess) {
          }

          public void matchingFinished() {
          }

          public ProgressIndicator getProgressIndicator() {
            return null;
          }
        },
        options.getMaxMatchesCount(),
        options.isDistinct(),
        options.isCaseSensitiveMatch()
      )
    );
    options.setScope(scope);
  }

  public CompiledOptions precompileOptions(List<Configuration> configurations) {
    List<Pair<MatchContext, Configuration>> contexts = new ArrayList<Pair<MatchContext, Configuration>>();

    for (Configuration configuration : configurations) {
      MatchContext matchContext = new MatchContext();
      matchContext.setMatcher(visitor);
      MatchOptions matchOptions = configuration.getMatchOptions();
      matchContext.setOptions(matchOptions);

      try {
        CompiledPattern compiledPattern = PatternCompiler.compilePattern(project, matchOptions);
        matchContext.setPattern(compiledPattern);
        contexts.add(Pair.create(matchContext, configuration));
      }
      catch (UnsupportedPatternException ignored) {}
      catch (MalformedPatternException ignored) {}
    }
    return new CompiledOptions(contexts);
  }

  Project getProject() {
    return project;
  }

  /**
   * Finds the matches of given pattern starting from given tree element.
   * @throws MalformedPatternException
   * @throws UnsupportedPatternException
   */
  protected void findMatches(MatchResultSink sink, final MatchOptions options) throws MalformedPatternException, UnsupportedPatternException
  {
    CompiledPattern compiledPattern = prepareMatching(sink, options);
    if (compiledPattern== null) {
      return;
    }

    matchContext.getSink().setMatchingProcess( scheduler );
    scheduler.init();
    progress = matchContext.getSink().getProgressIndicator();

    if (/*TokenBasedSearcher.canProcess(compiledPattern)*/ false) {
      //TokenBasedSearcher searcher = new TokenBasedSearcher(this);
      //searcher.search(compiledPattern);
      if (isTesting) {
        matchContext.getSink().matchingFinished();
        return;
      }
    }
    else {
      if (isTesting) {
        // testing mode;
        final PsiElement[] elements = ((LocalSearchScope)options.getScope()).getScope();

        PsiElement parent = elements[0].getParent();
        if (elements.length > 0 && matchContext.getPattern().getStrategy().continueMatching(parent != null ? parent : elements[0])) {
          visitor.matchContext(new SsrFilteringNodeIterator(new ArrayBackedNodeIterator(elements)));
        }
        else {
          for (PsiElement element : elements) {
            match(element);
          }
        }

        matchContext.getSink().matchingFinished();
        return;
      }
      if (!findMatches(options, compiledPattern)) {
        return;
      }
    }

    if (scheduler.getTaskQueueEndAction()==null) {
      scheduler.setTaskQueueEndAction(
        new Runnable() {
          public void run() {
            matchContext.getSink().matchingFinished();
          }
        }
      );
    }

    scheduler.executeNext();
  }

  private boolean findMatches(MatchOptions options, CompiledPattern compiledPattern) {
    LanguageFileType languageFileType = (LanguageFileType)options.getFileType();
    final StructuralSearchProfile profile = StructuralSearchUtil.getProfileByLanguage(languageFileType.getLanguage());
    assert profile != null;
    PsiElement node = compiledPattern.getNodes().current();
    final Language ourPatternLanguage = node != null ? profile.getLanguage(node) : ((LanguageFileType)options.getFileType()).getLanguage();
    final Language ourPatternLanguage2 = ourPatternLanguage == StdLanguages.XML ? StdLanguages.XHTML:null;
    SearchScope searchScope = compiledPattern.getScope();
    boolean ourOptimizedScope = searchScope != null;
    if (!ourOptimizedScope) searchScope = options.getScope();

    if (searchScope instanceof GlobalSearchScope) {
      GlobalSearchScope scope = (GlobalSearchScope)searchScope;
      if (scope.isSearchInLibraries()) {
        scope = new DelegatingGlobalSearchScope(scope) {
          final ProjectFileIndex myProjectFileIndex = ProjectFileIndex.SERVICE.getInstance(project);
          @Override
          public boolean contains(@NotNull VirtualFile file) {
            return super.contains(file) || myProjectFileIndex.isInLibrarySource(file);
          }
        };
      }
      final GlobalSearchScope finalScope = scope;
      final ContentIterator ci = new ContentIterator() {
        public boolean processFile(final VirtualFile fileOrDir) {
          if (!fileOrDir.isDirectory() && finalScope.contains(fileOrDir) && fileOrDir.getFileType() != FileTypes.UNKNOWN) {
            ++totalFilesToScan;
            scheduler.addOneTask(new MatchOneVirtualFile(fileOrDir, profile, ourPatternLanguage, ourPatternLanguage2));
          }
          return true;
        }
      };

      ApplicationManager.getApplication().runReadAction(new Runnable() {
        @Override
        public void run() {
          FileBasedIndex.getInstance().iterateIndexableFiles(ci, project, progress);
        }
      });
      progress.setText2("");
    }
    else {
      final PsiElement[] elementsToScan = ((LocalSearchScope)searchScope).getScope();
      totalFilesToScan = elementsToScan.length;

      for (int i = 0; i < elementsToScan.length; ++i) {
        final PsiElement psiElement = elementsToScan[i];

        if (psiElement == null) continue;
        final Language language = psiElement.getLanguage();

        PsiFile file = psiElement instanceof PsiFile ? (PsiFile)psiElement : psiElement.getContainingFile();

        if (profile.isMyFile(file, language, ourPatternLanguage, ourPatternLanguage2)) {
          scheduler.addOneTask(new MatchOnePsiFile(psiElement));
        }
        if (ourOptimizedScope) elementsToScan[i] = null; // to prevent long PsiElement reference
      }
    }
    return true;
  }

  private CompiledPattern prepareMatching(final MatchResultSink sink, final MatchOptions options) {
    CompiledPattern savedPattern = null;

    if (matchContext.getOptions() == options && matchContext.getPattern() != null &&
        matchContext.getOptions().hashCode() == matchContext.getPattern().getOptionsHashStamp()) {
      savedPattern = matchContext.getPattern();
    }

    matchContext.clear();
    matchContext.setSink(
      new MatchConstraintsSink(
        sink,
        options.getMaxMatchesCount(),
        options.isDistinct(),
        options.isCaseSensitiveMatch()
      )
    );
    matchContext.setOptions(options);
    matchContext.setMatcher(visitor);
    visitor.setMatchContext(matchContext);

    CompiledPattern compiledPattern = savedPattern;

    if (compiledPattern == null) {

      synchronized(getClass()) {
        final LastMatchData data = lastMatchData != null ? lastMatchData.get():null;
        if (data != null && options == data.lastOptions) {
          compiledPattern = data.lastPattern;
        }
        lastMatchData = null;
      }

      if (compiledPattern==null) {
        compiledPattern = ApplicationManager.getApplication().runReadAction(new Computable<CompiledPattern>() {
          @Override
          public CompiledPattern compute() {
            return PatternCompiler.compilePattern(project,options);
          }
        });
      }
    }

    cacheCompiledPattern(options, compiledPattern);
    return compiledPattern;
  }

  private void cacheCompiledPattern(final MatchOptions options, final CompiledPattern compiledPattern) {
    matchContext.setPattern(compiledPattern);
    compiledPattern.setOptionsHashStamp(options.hashCode());
  }

  /**
   * Finds the matches of given pattern starting from given tree element.
   * @param sink match result destination
   * @throws MalformedPatternException
   * @throws UnsupportedPatternException
   */
  protected void testFindMatches(MatchResultSink sink, MatchOptions options)
    throws MalformedPatternException, UnsupportedPatternException {
    isTesting = true;
    try {
      findMatches(sink,options);
    } finally {
      isTesting = false;
    }
  }

  /**
   * Finds the matches of given pattern starting from given tree element.
   * @param source string for search
   * @param pattern to be searched
   * @return list of matches found
   * @throws MalformedPatternException
   * @throws UnsupportedPatternException
   */
  protected List testFindMatches(String source,
                                 String pattern,
                                 MatchOptions options,
                                 boolean filePattern,
                                 FileType sourceFileType,
                                 String sourceExtension,
                                 boolean physicalSourceFile)
    throws MalformedPatternException, UnsupportedPatternException {

    CollectingMatchResultSink sink = new CollectingMatchResultSink();

    try {
      PsiElement[] elements = MatcherImplUtil.createSourceTreeFromText(source,
                                                                       filePattern ? PatternTreeContext.File : PatternTreeContext.Block,
                                                                       sourceFileType,
                                                                       sourceExtension,
                                                                       project, physicalSourceFile);

      options.setSearchPattern(pattern);
      options.setScope(new LocalSearchScope(elements));
      testFindMatches(sink, options);
    }
    catch (IncorrectOperationException e) {
      MalformedPatternException exception = new MalformedPatternException();
      exception.initCause(e);
      throw exception;
    }

    return sink.getMatches();
  }

  protected List testFindMatches(String source, String pattern, MatchOptions options, boolean filePattern) {
    return testFindMatches(source, pattern, options, filePattern, options.getFileType(), null, false);
  }

  class TaskScheduler implements MatchingProcess {
    private LinkedList<Runnable> tasks = new LinkedList<Runnable>();
    private boolean ended;
    private Runnable taskQueueEndAction;

    private boolean suspended;

    public void stop() {
      ended = true;
    }

    public void pause() {
      suspended = true;
    }

    public void resume() {
      if (!suspended) return;
      suspended = false;
      executeNext();
    }

    public boolean isSuspended() {
      return suspended;
    }

    public boolean isEnded() {
      return ended;
    }

    void setTaskQueueEndAction(Runnable taskQueueEndAction) {
      this.taskQueueEndAction = taskQueueEndAction;
    }
    Runnable getTaskQueueEndAction () {
      return taskQueueEndAction;
    }

    void addOneTask(Runnable runnable) {
      tasks.add(runnable);
    }

    private void executeNext() {
      while(!suspended && !ended) {
        if (tasks.isEmpty()) {
          ended = true;
          break;
        }

        final Runnable task = tasks.removeFirst();
        try {
          task.run();
        } catch (ProcessCanceledException e) {
          tasks.clear();
          ended = true;
          throw e;
        }
        catch (Throwable th) {
          LOG.error(th);
        }
      }

      if (ended) clearSchedule();
    }

    private void init() {
      ended = false;
      suspended = false;
      PsiManager.getInstance(project).startBatchFilesProcessingMode();
    }

    private void clearSchedule() {
      if (tasks != null) {
        taskQueueEndAction.run();
        PsiManager.getInstance(project).finishBatchFilesProcessingMode();
        tasks = null;
      }
    }

  }

  private class MatchOnePsiFile extends MatchOneFile {
    private PsiElement file;

    MatchOnePsiFile(PsiElement file) {
      this.file = file;
    }

    @Nullable
    @Override
    protected List<PsiElement> getPsiElementsToProcess() {
      PsiElement file = this.file;
      this.file = null;
      return new SmartList<PsiElement>(file);
    }
  }

  private abstract class MatchOneFile implements Runnable {
    public void run() {
      List<PsiElement> files = getPsiElementsToProcess();

      if (progress!=null) {
        progress.setFraction((double)scannedFilesCount/totalFilesToScan);
      }

      ++scannedFilesCount;

      if (files == null || files.size() == 0) return;
      final PsiFile psiFile = files.get(0).getContainingFile();

      if (psiFile!=null) {
        final Runnable action = new Runnable() {
          public void run() {
            ApplicationManager.getApplication().runWriteAction(new Runnable() {
              public void run() {
                if (project.isDisposed()) return;
                final PsiDocumentManager manager = PsiDocumentManager.getInstance(project);
                Document document = manager.getDocument(psiFile);
                if (document != null) manager.commitDocument(document);
              }
            });
          }
        };

        if (ApplicationManager.getApplication().isDispatchThread()) {
          action.run();
        } else {
          ApplicationManager.getApplication().invokeAndWait(
            action,
            ModalityState.defaultModalityState()
          );
        }
      }

      if (project.isDisposed()) return;

      for(PsiElement file:files) {
        if (file instanceof PsiFile) {
          matchContext.getSink().processFile((PsiFile)file);
        }

        final PsiElement finalFile = file;
        ApplicationManager.getApplication().runReadAction(
          new Runnable() {
            public void run() {
              PsiElement file = finalFile;
              if (!file.isValid()) return;
              if (file instanceof PsiIdentifier) {
                // Searching in previous results
                file = file.getParent();
              }

              match(file);
            }
          }
        );
      }
    }

    protected abstract @Nullable List<PsiElement> getPsiElementsToProcess();
  }

  // Initiates the matching process for given element
  // @param element the current search tree element
  public void match(PsiElement element) {
    MatchingStrategy strategy = matchContext.getPattern().getStrategy();

    if (strategy.continueMatching(element)) {
      visitor.matchContext(new ArrayBackedNodeIterator(new PsiElement[] {element}));
      return;
    }
    for(PsiElement el=element.getFirstChild();el!=null;el=el.getNextSibling()) {
      match(el);
    }
    if (element instanceof PsiLanguageInjectionHost) {
      InjectedLanguageUtil.enumerate(element, new PsiLanguageInjectionHost.InjectedPsiVisitor() {
        @Override
        public void visit(@NotNull PsiFile injectedPsi, @NotNull List<PsiLanguageInjectionHost.Shred> places) {
          match(injectedPsi);
        }
      });
    }
  }

  @Nullable
  protected MatchResult isMatchedByDownUp(PsiElement element, final MatchOptions options) {
    final CollectingMatchResultSink sink = new CollectingMatchResultSink();
    CompiledPattern compiledPattern = prepareMatching(sink, options);

    if (compiledPattern== null) {
      assert false;
      return null;
    }

    PsiElement targetNode = compiledPattern.getTargetNode();
    PsiElement elementToStartMatching = null;

    if (targetNode == null) {
      targetNode = compiledPattern.getNodes().current();
      if (targetNode != null) {
        compiledPattern.getNodes().advance();
        assert !compiledPattern.getNodes().hasNext();
        compiledPattern.getNodes().rewind();

        while (element.getClass() != targetNode.getClass()) {
          element = element.getParent();
          if (element == null)  return null;
        }

        elementToStartMatching = element;
      }
    } else {
      if (targetNode instanceof PsiIdentifier) {
        targetNode = targetNode.getParent();
        final PsiElement parent = targetNode.getParent();
        if (parent instanceof PsiTypeElement || parent instanceof PsiStatement) targetNode = parent;
      }

      MatchingHandler handler = null;

      while (element.getClass() == targetNode.getClass() ||
             compiledPattern.isTypedVar(targetNode) && compiledPattern.getHandler(targetNode).canMatch(targetNode, element)
            ) {
        handler = compiledPattern.getHandler(targetNode);
        handler.setPinnedElement(element);
        elementToStartMatching = element;
        if (handler instanceof TopLevelMatchingHandler) break;
        element = element.getParent();
        targetNode = targetNode.getParent();

        if (options.isLooseMatching()) {
          element = updateCurrentNode(element);
          targetNode = updateCurrentNode(targetNode);
        }
      }

      if (!(handler instanceof TopLevelMatchingHandler)) return null;
    }

    assert targetNode != null : "Could not match down up when no target node";

    match(elementToStartMatching);
    matchContext.getSink().matchingFinished();
    final int matchCount = sink.getMatches().size();
    assert matchCount <= 1;
    return matchCount > 0 ? sink.getMatches().get(0) : null;
  }

  private static PsiElement updateCurrentNode(PsiElement targetNode) {
    if (targetNode instanceof PsiCodeBlock && ((PsiCodeBlock)targetNode).getStatements().length == 1) {
      PsiElement targetNodeParent = targetNode.getParent();
      if (targetNodeParent instanceof PsiBlockStatement) {
        targetNodeParent = targetNodeParent.getParent();
      }

      if (targetNodeParent instanceof PsiIfStatement || targetNodeParent instanceof PsiLoopStatement) {
        targetNode = targetNodeParent;
      }
    }
    return targetNode;
  }

  private class MatchOneVirtualFile extends MatchOneFile {
    private final VirtualFile myFileOrDir;
    private final StructuralSearchProfile myProfile;
    private final Language myOurPatternLanguage;
    private final Language myOurPatternLanguage2;

    public MatchOneVirtualFile(VirtualFile fileOrDir,
                               StructuralSearchProfile profile,
                               Language ourPatternLanguage,
                               Language ourPatternLanguage2) {
      myFileOrDir = fileOrDir;
      myProfile = profile;
      myOurPatternLanguage = ourPatternLanguage;
      myOurPatternLanguage2 = ourPatternLanguage2;
    }

    @Nullable
    @Override
    protected List<PsiElement> getPsiElementsToProcess() {
      return ApplicationManager.getApplication().runReadAction(new Computable<List<PsiElement>>() {
        @Override
        public List<PsiElement> compute() {
          PsiFile file = PsiManager.getInstance(project).findFile(myFileOrDir);
          if (file == null) {
            return null;
          }

          final FileViewProvider viewProvider = file.getViewProvider();
          List<PsiElement> elementsToProcess = new SmartList<PsiElement>();

          for(Language lang: viewProvider.getLanguages()) {
            if (myProfile.isMyFile(file, lang, myOurPatternLanguage, myOurPatternLanguage2)) {
              elementsToProcess.add(viewProvider.getPsi(lang));
            }
          }

          return elementsToProcess;
        }
      });
    }
  }
}
