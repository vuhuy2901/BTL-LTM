package com.nhom8.server;

import com.nhom8.server.model.*;
import com.nhom8.server.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class ScoringPersistenceTest {
    @Autowired TestEntityManager entities;
    @Autowired UserRepository users;
    @Autowired MatchRepository matches;
    @Autowired MatchResultRepository results;

    @Test void historicalRowsUseLegacyDefaultsWithoutRecalculatingScores() {
        User user = users.saveAndFlush(new User("legacy_score", "password", "Legacy Score"));
        user.setRankingScore(1234); users.saveAndFlush(user);
        entities.getEntityManager().createNativeQuery("INSERT INTO matches(room_id,started_at) VALUES ('legacy-room',CURRENT_TIMESTAMP)").executeUpdate();
        Match legacy = entities.getEntityManager().createQuery("select m from Match m where m.roomId='legacy-room'",Match.class).getSingleResult();
        entities.getEntityManager().createNativeQuery("INSERT INTO match_results(match_id,user_id,points_drawn,points_guess) VALUES (?1,?2,80,100)")
                .setParameter(1,legacy.getMatchId()).setParameter(2,user.getUserId()).executeUpdate();
        entities.clear();
        MatchResult history = results.findByUserWithMatchOrderByStartedAtDesc(users.findById(user.getUserId()).orElseThrow()).getFirst();
        assertEquals(1,history.getMatch().getScoringVersion()); assertEquals(180,history.getTotalPoints());
        assertEquals(0,history.getPointsAdjustment());
        assertEquals(1234,users.findById(user.getUserId()).orElseThrow().getRankingScore());
    }

    @Test void newVersionPersistsNegativeDrawingScoresAndSeparateRewards() {
        User user = users.saveAndFlush(new User("new_score", "password", "New Score"));
        Match match = matches.saveAndFlush(new Match("new-room"));
        MatchResult result = new MatchResult(match,user);
        result.addDrawnPoints(-20); result.addGuessPoints(100); result.addAdjustmentPoints(20);
        results.saveAndFlush(result); entities.clear();
        MatchResult stored = results.findById(result.getResultId()).orElseThrow();
        assertEquals(4,stored.getMatch().getScoringVersion());
        assertEquals(-20,stored.getPointsDrawn()); assertEquals(100,stored.getPointsGuess());
        assertEquals(20,stored.getPointsAdjustment()); assertEquals(80,stored.getGameplayPoints()); assertEquals(100,stored.getTotalPoints());
    }
}
