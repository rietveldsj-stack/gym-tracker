package com.gymtracker.workout;

import static java.util.stream.Collectors.groupingBy;

import com.gymtracker.common.BadRequestException;
import com.gymtracker.common.ConflictException;
import com.gymtracker.common.NotFoundException;
import com.gymtracker.exercise.Exercise;
import com.gymtracker.exercise.ExerciseRepository;
import com.gymtracker.exercise.MuscleGroup;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class WorkoutService {

    private static final BigDecimal QUARTERS_PER_KG = BigDecimal.valueOf(4);

    private final WorkoutSessionRepository sessions;
    private final WorkoutSetRepository sets;
    private final ExerciseRepository exercises;

    public WorkoutService(WorkoutSessionRepository sessions, WorkoutSetRepository sets, ExerciseRepository exercises) {
        this.sessions = sessions;
        this.sets = sets;
        this.exercises = exercises;
    }

    public SessionResponse start(UUID userId, UUID id, StartSessionRequest request) {
        Optional<WorkoutSession> existing = sessions.findById(id);
        if (existing.isPresent()) {
            if (!existing.get().isOwnedBy(userId)) {
                throw new NotFoundException("Workout not found");
            }
            return toResponse(existing.get());
        }
        if (sessions.findFirstByUserIdAndEndedAtIsNull(userId).isPresent()) {
            throw new ConflictException("Another workout is already in progress");
        }
        return toResponse(sessions.save(new WorkoutSession(id, userId, request.date(), request.startedAt())));
    }

    public EndSessionResponse end(UUID userId, UUID id, EndSessionRequest request) {
        Optional<WorkoutSession> found = sessions.findByIdAndUserId(id, userId);
        if (found.isEmpty()) {
            return EndSessionResponse.discardedResult();
        }
        WorkoutSession session = found.get();
        if (session.getEndedAt() != null) {
            return EndSessionResponse.ended(toResponse(session));
        }
        if (request.endedAt().isBefore(session.getStartedAt())) {
            throw new BadRequestException("endedAt must not be before startedAt");
        }
        if (sets.countBySessionId(id) == 0) {
            sessions.delete(session);
            return EndSessionResponse.discardedResult();
        }
        session.end(request.endedAt());
        return EndSessionResponse.ended(toResponse(session));
    }

    public void discard(UUID userId, UUID id) {
        sessions.findByIdAndUserId(id, userId).ifPresent(session -> {
            sets.deleteBySessionId(id);
            sessions.delete(session);
        });
    }

    @Transactional(readOnly = true)
    public Optional<SessionResponse> active(UUID userId) {
        return sessions.findFirstByUserIdAndEndedAtIsNull(userId).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public SessionResponse get(UUID userId, UUID id) {
        return sessions.findByIdAndUserId(id, userId).map(this::toResponse)
                .orElseThrow(() -> new NotFoundException("Workout not found"));
    }

    @Transactional(readOnly = true)
    public List<SessionSummary> history(UUID userId) {
        List<WorkoutSession> ended = sessions.findByUserIdAndEndedAtIsNotNullOrderByStartedAtDesc(userId);
        List<WorkoutSet> allSets = sets.findBySessionIdIn(ended.stream().map(WorkoutSession::getId).toList());
        Map<UUID, List<WorkoutSet>> setsBySession = allSets.stream().collect(groupingBy(WorkoutSet::getSessionId));
        Map<UUID, Exercise> exerciseById = exercisesFor(allSets);
        return ended.stream().map(session -> {
            List<WorkoutSet> sessionSets = setsBySession.getOrDefault(session.getId(), List.of());
            List<MuscleGroup> groups = sessionSets.stream()
                    .sorted(Comparator.comparing(WorkoutSet::getLoggedAt))
                    .map(set -> exerciseById.get(set.getExerciseId()).getMuscleGroup())
                    .distinct()
                    .toList();
            long seconds = Duration.between(session.getStartedAt(), session.getEndedAt()).toSeconds();
            return new SessionSummary(session.getId(), session.getDate(), session.getStartedAt(), session.getEndedAt(),
                    seconds, sessionSets.size(), groups);
        }).toList();
    }

    public SetResponse putSet(UUID userId, UUID id, SetRequest request) {
        if (!isQuarterStep(request.weightKg())) {
            throw new BadRequestException("weightKg must be a multiple of 0.25");
        }
        sessions.findByIdAndUserId(request.sessionId(), userId)
                .orElseThrow(() -> new NotFoundException("Workout not found"));
        Exercise exercise = exercises.findByIdAndUserId(request.exerciseId(), userId)
                .orElseThrow(() -> new NotFoundException("Exercise not found"));
        Optional<WorkoutSet> existing = sets.findById(id);
        if (existing.isPresent()) {
            WorkoutSet set = existing.get();
            if (!set.getSessionId().equals(request.sessionId())) {
                boolean mine = sessions.findByIdAndUserId(set.getSessionId(), userId).isPresent();
                throw mine ? new ConflictException("This set belongs to another workout")
                        : new NotFoundException("Set not found");
            }
            set.update(request.exerciseId(), request.weightKg(), request.reps(), request.type(), request.loggedAt());
            return SetResponse.of(set, exercise);
        }
        if (exercise.isArchived()) {
            throw new ConflictException("This exercise has been deleted");
        }
        WorkoutSet set = sets.save(new WorkoutSet(id, request.sessionId(), request.exerciseId(), request.weightKg(),
                request.reps(), request.type(), request.loggedAt()));
        return SetResponse.of(set, exercise);
    }

    /** Finished workouts can be edited too; one left without sets is deleted, as an empty workout is never kept. */
    public void deleteSet(UUID userId, UUID id) {
        sets.findById(id).ifPresent(set -> sessions.findByIdAndUserId(set.getSessionId(), userId).ifPresent(session -> {
            sets.delete(set);
            if (session.getEndedAt() != null && sets.countBySessionId(session.getId()) == 0) {
                sessions.delete(session);
            }
        }));
    }

    static boolean isQuarterStep(BigDecimal weight) {
        return weight.multiply(QUARTERS_PER_KG).stripTrailingZeros().scale() <= 0;
    }

    private SessionResponse toResponse(WorkoutSession session) {
        List<WorkoutSet> sessionSets = sets.findBySessionIdOrderByLoggedAtAsc(session.getId());
        Map<UUID, Exercise> exerciseById = exercisesFor(sessionSets);
        List<SetResponse> setResponses = sessionSets.stream()
                .map(set -> SetResponse.of(set, exerciseById.get(set.getExerciseId())))
                .toList();
        return new SessionResponse(session.getId(), session.getDate(), session.getStartedAt(), session.getEndedAt(),
                setResponses);
    }

    private Map<UUID, Exercise> exercisesFor(Collection<WorkoutSet> workoutSets) {
        List<UUID> ids = workoutSets.stream().map(WorkoutSet::getExerciseId).distinct().toList();
        return exercises.findAllById(ids).stream().collect(Collectors.toMap(Exercise::getId, Function.identity()));
    }
}
