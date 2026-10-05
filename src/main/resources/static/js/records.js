// PR detection for the open session (spec §5). Runs on the phone so it also works offline.
const key = (weightKg) => Number(weightKg).toFixed(2);
const byTime = (a, b) => Date.parse(a.loggedAt) - Date.parse(b.loggedAt);

function initialState(records) {
  const repRecords = records?.repRecords ?? [];
  const weights = repRecords.map((r) => Number(r.weightKg));
  if (records?.heaviest) weights.push(Number(records.heaviest.weightKg));
  return {
    hasAny: weights.length > 0,
    maxWeight: weights.length ? Math.max(...weights) : 0,
    bestReps: new Map(repRecords.map((r) => [key(r.weightKg), r.reps])),
  };
}

/** Map(setId -> {heaviest, reps}) for the WORK sets, comparing each with stored records and earlier sets. */
export function prFlags(sets, recordsByExercise) {
  const states = new Map();
  const flags = new Map();
  for (const set of [...sets].sort(byTime)) {
    if (set.type !== 'WORK') continue;
    if (!states.has(set.exerciseId)) states.set(set.exerciseId, initialState(recordsByExercise[set.exerciseId]));
    const state = states.get(set.exerciseId);
    const weight = Number(set.weightKg);
    const best = state.bestReps.get(key(weight));
    flags.set(set.id, {
      heaviest: weight > 0 && state.hasAny && weight > state.maxWeight,
      reps: best !== undefined && set.reps > best,
    });
    state.hasAny = true;
    state.maxWeight = Math.max(state.maxWeight, weight);
    state.bestReps.set(key(weight), Math.max(best ?? 0, set.reps));
  }
  return flags;
}

export function isPr(flag) {
  return Boolean(flag && (flag.heaviest || flag.reps));
}

export function recordsByExercise(exercises) {
  return Object.fromEntries(exercises.map((e) => [e.id, e.records]));
}
