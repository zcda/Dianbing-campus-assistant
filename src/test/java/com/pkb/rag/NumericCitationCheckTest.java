package com.pkb.rag;

import com.pkb.search.Source;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NumericCitationCheckTest {
    private final List<Source> sources = List.of(
            new Source(1, 1, "软件工程培养方案", 221,
                    "软件工程：课程总学分不低于 23学分。", 0.8),
            new Source(2, 1, "软件工程培养方案", 222,
                    "软件工程：学位课要求不低于 14学分。", 0.7));

    @Test
    void mappedCitationCanStillMissTheClaimItPurportsToSupport() {
        var audit = NumericCitationCheck.check(
                "根据培养方案[1]，学位课要求不低于 14学分，课程总学分不低于 23学分。", sources);

        assertEquals(2, audit.total());
        assertEquals(1, audit.supported());
        assertEquals(List.of("学位课 14学分"), audit.unsupported());
        assertEquals(0.5, audit.coverage());
    }

    @Test
    void bothCreditClaimsHaveMatchingCitedSources() {
        var audit = NumericCitationCheck.check(
                "学位课要求不低于 14学分。[2] 课程总学分不低于 23学分。[1]", sources);

        assertEquals(2, audit.supported());
        assertEquals(1.0, audit.coverage());
    }

    @Test
    void unrelatedAnswerIsOutsideThisNarrowAudit() {
        var audit = NumericCitationCheck.check("选课需考虑研究方向和校区。[1]", sources);

        assertEquals(0, audit.total());
        assertEquals(null, audit.coverage());
    }
}
