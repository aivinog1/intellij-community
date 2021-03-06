import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

class Test {
  {
    final Map<Comparable, List<Collection<?>>> families = sortingMerge(<error descr="Incompatible types. Found: 'java.util.Map<C,java.util.List<java.lang.Object>>', required: 'java.util.Map<java.lang.Comparable,java.util.List<java.util.Collection<?>>>'">(s) -> 0</error>);
  }

  private <C extends Comparable<C>, T> Map<C, List<T>> sortingMerge(Function<T, C> keyFunction) {

    return new HashMap<C, List<T>>();
  }
}
