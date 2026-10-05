package com.gymtracker.workout;

public record EndSessionResponse(boolean discarded, SessionResponse session) {

    static EndSessionResponse discardedResult() {
        return new EndSessionResponse(true, null);
    }

    static EndSessionResponse ended(SessionResponse session) {
        return new EndSessionResponse(false, session);
    }
}
