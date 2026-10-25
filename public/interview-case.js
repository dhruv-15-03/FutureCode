import { splitSourceLines } from './exercise.js';

export const CASE_ID = 'missing-requests-v1';
export const COACH_MAX_TURNS = 6;
export const CASE_MAX_PINS = 6;
export const CASE_STAGES = ['Read the flow', 'Reproduce & challenge', 'Patch & test', 'Debrief'];
export const ARTIFACT_LIMITS = {
  flow: 2000, questions: 1000, critique: 2000, patch: 6000,
  patchReasoning: 1200, testPlan: 2400, verification: 1200
};

const originalRecord = `  public void record(String route) {
    int previous = counts.getOrDefault(route, 0);
    counts.put(route, previous + 1);
  }`;
const proposedRecord = `  public void record(String route) {
    counts.putIfAbsent(route, 0);
    int previous = counts.getOrDefault(route, 0);
    counts.replace(route, previous, previous + 1);
  }`;
const fixedRecord = `  public void record(String route) {
    counts.merge(route, 1, Integer::sum);
  }`;
const counterSource = `import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class RequestCounter {
  private final ConcurrentMap<String, Integer> counts;

  public RequestCounter() {
    this(new ConcurrentHashMap<>());
  }

  RequestCounter(ConcurrentMap<String, Integer> counts) {
    this.counts = counts;
  }

${originalRecord}

  public int countFor(String route) {
    return counts.getOrDefault(route, 0);
  }
}`;

export const practiceCase = {
  id: CASE_ID,
  title: 'The missing requests',
  duration: '20-30 minutes',
  summary: 'The request log says 10,000 requests completed. The traffic report counts fewer. A confident AI proposal claims to fix the race. Investigate before accepting it.',
  requirements: [
    'Count every completed handler invocation once, including handlers that throw.',
    'Keep counts independent for each route, with concurrent calls allowed.',
    'Read a report only after the relevant handlers have completed.',
    'Prefer per-key coordination; do not serialize unrelated routes without explaining the tradeoff.'
  ],
  assumptions: [
    'Java 17+; a framework-neutral service excerpt, not a complete HTTP application.',
    'One shared RequestCounter instance is injected into both services.',
    'Routes are canonical non-null strings; counts remain within the integer range.',
    'Incident observations are authored practice fixtures, not a live load test.'
  ],
  observations: [
    ['Sequential /checkout run', '2,000 completed', 'report: 2,000'],
    ['Concurrent /checkout run', '10,000 completed', 'report: 7,421'],
    ['Repeat concurrent run', '10,000 completed', 'report: 7,806'],
    ['Success + failed handler', '2 completed', 'report: 2 (sequential)']
  ],
  files: [
    {
      name: 'RequestController.java', role: 'Request completion boundary',
      source: `public final class RequestController {
  private final RequestCounter counter;

  public RequestController(RequestCounter counter) {
    this.counter = counter;
  }

  public void handle(String route, Runnable handler) {
    try {
      handler.run();
    } finally {
      counter.record(route);
    }
  }
}`
    },
    { name: 'RequestCounter.java', role: 'Shared per-route state', source: counterSource },
    {
      name: 'TrafficReportService.java', role: 'Reporting read path',
      source: `public final class TrafficReportService {
  private final RequestCounter counter;

  public TrafficReportService(RequestCounter counter) {
    this.counter = counter;
  }

  public TrafficReport snapshot(String route) {
    return new TrafficReport(route, counter.countFor(route));
  }
}`
    },
    {
      name: 'TrafficReport.java', role: 'Immutable report value',
      source: `public record TrafficReport(String route, int completedRequests) {}`
    },
    {
      name: 'RequestCounterTest.java', role: 'Controlled regression fixture',
      source: `import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class RequestCounterTest {
  public static void main(String[] args) throws Exception {
    RequestCounter counter = new RequestCounter();
    RequestController controller = new RequestController(counter);
    controller.handle("/checkout", () -> {});
    try {
      controller.handle("/checkout", () -> {
        throw new IllegalStateException("handler failed");
      });
      throw new AssertionError("handler exception was swallowed");
    } catch (IllegalStateException expected) {
      // A completed failed handler is counted too.
    }
    equal("success-and-failure", 2,
        new TrafficReportService(counter).snapshot("/checkout").completedRequests());
    counter.record("/health");
    equal("route-isolation", 1, counter.countFor("/health"));
    equal("checkout-unchanged", 2, counter.countFor("/checkout"));

    int actual = forcedOverlap();
    System.out.println("forced-overlap expected=2 actual=" + actual);
    equal("forced-overlap", 2, actual);
    System.out.println("PASS: all reference checks");
  }

  private static int forcedOverlap() throws Exception {
    RequestCounter counter = new RequestCounter(new ReadBarrierMap());
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Runnable record = () -> {
      try {
        counter.record("/checkout");
      } catch (Throwable error) {
        failure.compareAndSet(null, error);
      }
    };
    Thread a = new Thread(record, "case-worker-a");
    Thread b = new Thread(record, "case-worker-b");
    a.start();
    b.start();
    a.join(3000);
    b.join(3000);
    if (a.isAlive() || b.isAlive()) {
      a.interrupt();
      b.interrupt();
      throw new AssertionError("worker did not finish");
    }
    if (failure.get() != null) throw new AssertionError(failure.get());
    return counter.countFor("/checkout");
  }

  private static void equal(String name, int expected, int actual) {
    System.out.println(name + " expected=" + expected + " actual=" + actual);
    if (actual != expected) throw new AssertionError(name);
  }

  // Test-only scheduling: both workers observe the old value before either returns.
  private static final class ReadBarrierMap
      extends ConcurrentHashMap<String, Integer> {
    private final CountDownLatch reads = new CountDownLatch(2);

    @Override
    public Integer getOrDefault(Object key, Integer fallback) {
      Integer value = super.getOrDefault(key, fallback);
      if (Thread.currentThread().getName().startsWith("case-worker-")) {
        reads.countDown();
        try {
          if (!reads.await(2, TimeUnit.SECONDS)) {
            throw new AssertionError("second read did not arrive");
          }
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new AssertionError(error);
        }
      }
      return value;
    }
  }
}`
    }
  ],
  proposal: {
    label: 'Seeded AI proposal',
    disclosure: 'An authored practice proposal, not a live model response. Assess its claim against the case.',
    claim: 'Use compare-and-replace so concurrent requests cannot overwrite each other. One conditional replacement is enough; no retry is necessary.',
    code: proposedRecord
  },
  hints: [
    'Trace the finally block into the shared counter, then follow the reporting read path. A failed handler still reaches finally.',
    'Try making both requests read the same old count before either writes. A concurrent map does not make the whole sequence atomic.',
    'A conditional replace can return false. If that result is ignored, which completed request contributed no increment? An atomic merge is one repair.'
  ],
  reference: {
    explanation: 'Both requests can read 0 and then each write 1. The map safely performs each individual operation, but the read-modify-write sequence is not atomic. The proposed compare-and-replace still loses an increment when one replacement fails and its false return is ignored.',
    flow: 'RequestController.handle runs the handler, then finally records completion even on an exception. RequestCounter owns the shared per-route map. TrafficReportService reads that same counter after completion and returns an immutable TrafficReport.',
    fix: 'Use ConcurrentHashMap.merge for the per-route increment. A correctly retried compare-and-replace loop or a lock covering the entire read/write can also be correct; discuss contention and ownership.',
    counterSource: counterSource.replace(originalRecord, fixedRecord),
    proposedCounterSource: counterSource.replace(originalRecord, proposedRecord),
    test: 'Force both workers to observe the initial value before either write. The required final count is 2. The original method and seeded proposal both yield 1 under this schedule. The reference merge yields 2. Also preserve failed-handler counting and route isolation.',
    review: [
      ['Code understanding', 'Does the explanation connect completion, shared state, and reporting across files, including the exception path?'],
      ['Diagnosis', 'Does the reasoning show a legal interleaving rather than merely naming concurrency?'],
      ['AI judgment', 'Does the critique identify the ignored false result of replace and verify the proposal instead of trusting its confidence?'],
      ['Patch reasoning', 'Does the written patch actually implement an atomic per-route update and explain tradeoffs? This needs review; it is not parsed or run.'],
      ['Verification quality', 'Does the proposed test control overlap, assert 2, preserve error-path counting, and isolate routes? Written test code is not run.']
    ]
  }
};

export const counterFile = practiceCase.files.find(file => file.name === 'RequestCounter.java');

export function createAttempt() {
  return {
    stage: 0, furthest: 0, file: practiceCase.files[0].name, startedAt: null, completedAt: null,
    pins: new Set(), schedule: [], hints: 0, flow: '', questions: '', critique: '',
    aiDecision: '', patch: counterFile.source, patchReasoning: '', invariant: '',
    expectedCount: '', testPlan: '', verification: ''
  };
}

export function traceSchedule(schedule) {
  if (!Array.isArray(schedule) || schedule.length > 4) throw new Error('A trace contains at most four operations.');
  const reads = new Map(), writes = new Set(), steps = [];
  let value = 0;
  for (const operation of schedule) {
    if (!['A:read', 'A:write', 'B:read', 'B:write'].includes(operation)) throw new Error('Unknown trace operation.');
    const [thread, action] = operation.split(':');
    if (action === 'read') {
      if (reads.has(thread)) throw new Error('Each request reads once.');
      reads.set(thread, value);
      steps.push({ operation, text: `${thread} reads ${value}`, value });
    } else {
      if (!reads.has(thread) || writes.has(thread)) throw new Error('A request must read before its one write.');
      value = reads.get(thread) + 1;
      writes.add(thread);
      steps.push({ operation, text: `${thread} writes ${value} from its captured ${reads.get(thread)}`, value });
    }
  }
  return { steps, value, completed: writes.size, finished: schedule.length === 4, lost: schedule.length === 4 && value < 2 };
}

export function attemptProblem(attempt, stage) {
  if (stage === 0) {
    if (!attempt.flow.trim()) return { field: 'case-flow', message: 'Explain the request-to-report path before continuing.' };
    if (!attempt.questions.trim()) return { field: 'case-questions', message: 'Record a clarifying question or an explicit assumption.' };
    const files = new Set([...attempt.pins].map(pin => pin.split('#')[0]));
    if (files.size < 2) return { field: 'case-source', message: 'Pin evidence from at least two different source files. This checks coverage, not relevance.' };
  } else if (stage === 1) {
    if (!traceSchedule(attempt.schedule).lost) return { field: 'case-trace', message: 'Find a four-operation schedule that loses an increment. Reset a serial trace and try overlapping the reads.' };
    if (!['accept', 'reject'].includes(attempt.aiDecision)) return { field: 'case-ai-decision', message: 'Commit an accept/reject decision about the seeded proposal.' };
    if (!attempt.critique.trim()) return { field: 'case-critique', message: 'Explain your decision about the AI proposal.' };
  } else if (stage === 2) {
    if (!attempt.patch.trim() || attempt.patch.trim() === counterFile.source.trim()) return { field: 'case-patch', message: 'Edit the proposed RequestCounter.java patch. Edits are recorded, not executed or automatically graded.' };
    for (const [field, label] of [['patchReasoning', 'Explain the patch and its tradeoffs.'], ['testPlan', 'Describe a concrete regression test.'], ['verification', 'State what still needs verification.']]) {
      if (!attempt[field].trim()) {
        const ids = { patchReasoning: 'case-patch-reasoning', testPlan: 'case-test-plan', verification: 'case-verification' };
        return { field: ids[field], message: label };
      }
    }
    if (!['atomic', 'container', 'report-lock', 'best-effort'].includes(attempt.invariant)) return { field: 'case-invariant', message: 'Choose the invariant your fix must guarantee.' };
    if (!/^[0-2]$/.test(attempt.expectedCount)) return { field: 'case-expected', message: 'Enter a final count of 0, 1, or 2 for two completed requests.' };
  }
  return null;
}

export function evaluateAttempt(attempt) {
  return [
    { label: 'Controlled lost-update schedule', passed: traceSchedule(attempt.schedule).lost, detail: 'Two reads of 0 followed by two writes of 1 lose one completed request.' },
    { label: 'Seeded proposal decision', passed: attempt.aiDecision === 'reject', detail: 'Reject the proposal: a failed conditional replacement is ignored instead of retried.' },
    { label: 'Required fix invariant', passed: attempt.invariant === 'atomic', detail: 'Each completed request must apply one atomic increment for its route.' },
    { label: 'Concurrent regression expectation', passed: attempt.expectedCount === '2', detail: 'After two completed calls, the required final count is 2, not 1.' }
  ];
}

export function practiceRecord(attempt, coachTurns) {
  return {
    caseId: CASE_ID, title: practiceCase.title, kind: 'practice-record-not-hiring-assessment',
    elapsedSeconds: Math.max(0, Math.round(((attempt.completedAt ?? Date.now()) - attempt.startedAt) / 1000)),
    hintsViewed: attempt.hints,
    curatedChecks: evaluateAttempt(attempt),
    requiresHumanReview: true,
    artifacts: Object.fromEntries(Object.keys(ARTIFACT_LIMITS).map(field => [field, attempt[field]])),
    selections: { aiDecision: attempt.aiDecision, invariant: attempt.invariant, expectedCount: attempt.expectedCount },
    evidence: [...attempt.pins].map(pin => {
      const [name, number] = pin.split('#');
      const file = practiceCase.files.find(item => item.name === name);
      return { file: name, line: Number(number), quote: splitSourceLines(file.source)[Number(number) - 1].trim() };
    }),
    controlledTrace: traceSchedule(attempt.schedule),
    coach: {
      requestsAttempted: coachTurns.length,
      repliesReceived: coachTurns.filter(turn => turn.response).length,
      mode: coachTurns.some(turn => turn.response) ? 'provider-replies-unverified' : 'seeded-proposal-only',
      turns: coachTurns.map(turn => ({
        prompt: turn.prompt, provider: turn.provider || 'openai', status: turn.status,
        response: turn.response ? {
          reply: turn.response.reply, assumptions: [...turn.response.assumptions],
          citations: turn.response.citations.map(citation => ({ file: citation.file, line: citation.line, quote: citation.quote }))
        } : null
      }))
    },
    limitations: 'Written explanations, candidate patch, test plan and AI collaboration quality are not automatically graded. This simulation did not execute the patch; separate external runs, if any, have their own records. Curated checks are inspectable learning references, not a hiring score.'
  };
}

const text = (value, max) => typeof value === 'string' && value.trim().length > 0 && value.length <= max;
const exactKeys = (value, keys) => value && typeof value === 'object' && !Array.isArray(value) &&
  Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key));

export const coachSchema = {
  type: 'object', additionalProperties: false, required: ['reply', 'assumptions', 'citations'],
  properties: {
    reply: { type: 'string' },
    assumptions: { type: 'array', maxItems: 3, items: { type: 'string' } },
    citations: {
      type: 'array', maxItems: 3,
      items: {
        type: 'object', additionalProperties: false, required: ['file', 'line', 'quote'],
        properties: {
          file: { type: 'string', enum: practiceCase.files.map(file => file.name) },
          line: { type: 'integer', minimum: 1 }, quote: { type: 'string' }
        }
      }
    }
  }
};

export function coachProblem(data) {
  const invalid = 'The coach returned an invalid response. No reply was loaded; try again.';
  if (!exactKeys(data, ['reply', 'assumptions', 'citations']) || !text(data.reply, 2000) ||
      !Array.isArray(data.assumptions) || data.assumptions.length > 3 ||
      !data.assumptions.every(item => text(item, 300)) ||
      !Array.isArray(data.citations) || data.citations.length > 3) return invalid;
  const cited = new Set();
  for (const citation of data.citations) {
    if (!exactKeys(citation, ['file', 'line', 'quote'])) return invalid;
    const file = practiceCase.files.find(item => item.name === citation.file);
    const lines = file ? splitSourceLines(file.source) : [];
    const key = `${citation.file}#${citation.line}`;
    if (!Number.isInteger(citation.line) || citation.line < 1 || citation.line > lines.length ||
        !text(citation.quote, 1200) || citation.quote !== lines[citation.line - 1].trim() || cited.has(key)) {
      return 'The coach cited source that does not match this case. No reply was loaded; try again.';
    }
    cited.add(key);
  }
  return '';
}
