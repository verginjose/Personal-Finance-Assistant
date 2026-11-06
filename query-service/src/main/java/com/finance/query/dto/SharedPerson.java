package com.finance.query.dto;

import java.util.List;
import java.util.UUID;

/** Someone the current user shares at least one accepted group with. */
public record SharedPerson(UUID userId, String name, int sharedGroupCount, List<String> sharedGroups) {}
