package com.gymtracker.stats;

import java.util.List;

public record Records(RecordSet heaviest, List<RecordSet> repRecords) {

    public static final Records EMPTY = new Records(null, List.of());
}
