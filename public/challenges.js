export const challenges = [
  {
    id: 'alias', title: 'The list that changed twice', kind: 'Predict behavior', level: 'Warm-up', time: '3 min',
    concept: 'References & mutation', file: 'Alias.java',
    code: `import java.util.*;

class Alias {
  public static void main(String[] args) {
    List<String> original = new ArrayList<>(List.of("A"));
    List<String> alias = original;
    List<String> copy = new ArrayList<>(original);
    alias.add("B");
    copy.add("C");
    System.out.println(original);
    System.out.println(copy);
  }
}`,
    questions: [{
      prompt: 'What do the two println calls output, in order?',
      options: ['[A, B] then [A, C]', '[A] then [A, C]', '[A, B, C] then [A, B, C]', '[A, B] then [A, B, C]'],
      correctIndex: 0,
      explanation: 'alias and original reference the same list. copy is a new list containing the original elements at the time of construction. Adding B changes original; adding C changes only copy.',
      lines: [6, 7, 8, 9]
    }]
  },
  {
    id: 'finally', title: 'Follow the return value', kind: 'Trace lines', level: 'Intermediate', time: '4 min',
    concept: 'Control flow & finally', file: 'ReturnTrace.java',
    code: `class ReturnTrace {
  static int calculate() {
    int value = 1;
    try {
      value = 2;
      return value;
    } finally {
      value = 3;
      System.out.print(value + " ");
    }
  }
  public static void main(String[] args) {
    System.out.println(calculate());
  }
}`,
    questions: [{
      prompt: 'Which trace explains the output?',
      options: ['Line 6 returns immediately; output is 2', 'Line 8 changes the returned value; output is 3 3', 'Line 6 captures 2, finally prints 3, then main prints 2', 'finally runs before try; output is 3 2'],
      correctIndex: 2,
      explanation: 'The return expression is evaluated at line 6, capturing the primitive value 2. The finally block then executes and prints 3 plus a space. Changing the local variable does not change the already captured return value. Output: 3 2.',
      lines: [6, 8, 9, 13]
    }]
  },
  {
    id: 'cache', title: 'A cache that forgets its keys', kind: 'Diagnose defect', level: 'Intermediate', time: '5 min',
    concept: 'HashMap & mutable keys', file: 'SessionCache.java',
    code: `import java.util.*;

class Key {
  int id;
  Key(int id) { this.id = id; }
  public int hashCode() { return id; }
  public boolean equals(Object o) {
    return o instanceof Key && ((Key) o).id == id;
  }
}
// Inside a method:
Map<Key, String> cache = new HashMap<>();
Key key = new Key(1);
cache.put(key, "session");
key.id = 2;
System.out.println(cache.get(key));`,
    questions: [{
      prompt: 'In this example the lookup returns null. What is the root cause and reliable fix?',
      options: ['HashMap compares only identity; use TreeMap', 'The hash changes after insertion; make equality/hash fields immutable', 'get deletes expired values; use containsKey', 'The map is too small; increase initial capacity'],
      correctIndex: 1,
      explanation: 'HashMap stores the insertion hash. Changing id changes hashCode and makes the lookup hash differ from the stored hash, even for the same object. Use immutable key identity (for example, a final id) or remove the key before mutation and reinsert it.',
      lines: [4, 6, 14, 15, 16]
    }]
  },
  {
    id: 'boundary', title: 'Catch the missing last item', kind: 'Select regression test', level: 'Warm-up', time: '3 min',
    concept: 'Boundary cases & test design', file: 'Totals.java',
    code: `class Totals {
  static int total(int[] prices) {
    int sum = 0;
    for (int i = 0; i < prices.length - 1; i++) {
      sum += prices[i];
    }
    return sum;
  }
}`,
    questions: [{
      prompt: 'Which smallest test fails now and passes when the loop uses i < prices.length?',
      options: ['total(new int[]{}) == 0', 'total(new int[]{0}) == 0', 'total(new int[]{7}) == 7', 'total(new int[]{0, 0}) == 0'],
      correctIndex: 2,
      explanation: 'A singleton nonzero array isolates the skipped final element. The buggy loop never runs and returns 0 rather than 7. Empty arrays and zero-only inputs mask the defect.',
      lines: [4, 5]
    }]
  },
  {
    id: 'patch', title: 'The AI patch that loses updates', kind: 'Critique AI patch', level: 'Advanced', time: '6 min',
    concept: 'Concurrency & atomicity', file: 'RequestCounter.java',
    code: `import java.util.concurrent.*;

class RequestCounter {
  // AI patch: replace HashMap with ConcurrentHashMap.
  private final ConcurrentHashMap<String, Integer> counts =
      new ConcurrentHashMap<>();

  void record(String route) {
    int previous = counts.getOrDefault(route, 0);
    counts.put(route, previous + 1);
  }
}`,
    questions: [{
      prompt: 'Two threads call record("/api") concurrently. What did the AI patch miss?',
      options: ['ConcurrentHashMap is never thread-safe', 'The read-modify-write is not atomic; use counts.merge(route, 1, Integer::sum)', 'Integer must be replaced by int', 'getOrDefault always returns zero for concurrent maps'],
      correctIndex: 1,
      explanation: 'Individual map operations are safe, but two threads can read the same previous count and both write the same incremented count. merge performs the update atomically for this key. A concurrent collection alone does not make a multi-step algorithm atomic.',
      lines: [9, 10]
    }]
  }
];
