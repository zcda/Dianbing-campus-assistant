package com.pkb.search;

import java.util.Optional;

/** Removes a graduate cohort/program prefix so the remaining information need can be checked separately. */
public final class AcademicQuestionFocus {
    private AcademicQuestionFocus() { }

    public static Optional<String> extract(String question) {
        if (question == null || question.isBlank()) return Optional.empty();
        String stripped = question.strip().replaceFirst("^20\\d{2}级", "");
        int marker = stripped.indexOf("学硕");
        if (marker <= 0 || marker > 18) return Optional.empty();
        String focus = stripped.substring(marker + 2).strip();
        return focus.length() >= 4 ? Optional.of(focus) : Optional.empty();
    }

    /** A trailing clause after 的/后 often states the fact the user actually needs. */
    public static Optional<String> answerTarget(String focus) {
        if (focus == null || focus.isBlank()) return Optional.empty();
        int cut = Math.max(focus.lastIndexOf('的'), focus.lastIndexOf('后'));
        if (cut < 0 || cut >= focus.length() - 5) return Optional.empty();
        return Optional.of(focus.substring(cut + 1).strip());
    }
}
