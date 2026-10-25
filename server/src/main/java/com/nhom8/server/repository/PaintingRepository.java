package com.nhom8.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.nhom8.server.model.Painting;

/**
 * Repository quản lý thực thể Painting (thay thế PaintingDAO).
 */
@Repository
public interface PaintingRepository extends JpaRepository<Painting, Integer> {
    @org.springframework.data.jpa.repository.Query("SELECT p FROM Painting p JOIN FETCH p.match "
            + "WHERE p.painter.username = :username ORDER BY p.match.startedAt DESC, p.paintingId DESC")
    java.util.List<Painting> findByPainterUsernameWithMatch(
            @org.springframework.data.repository.query.Param("username") String username);

    boolean existsByMatch_MatchIdAndPainter_Username(Integer matchId, String username);

    @org.springframework.data.jpa.repository.Query("SELECT p FROM Painting p JOIN FETCH p.painter JOIN FETCH p.match "
            + "WHERE p.match.matchId = :matchId ORDER BY p.roundNumber, p.turnNumber, p.paintingId")
    java.util.List<Painting> findForMatch(@org.springframework.data.repository.query.Param("matchId") Integer matchId);

    @org.springframework.data.jpa.repository.Query("SELECT p FROM Painting p JOIN FETCH p.painter JOIN FETCH p.match "
            + "WHERE p.paintingId = :paintingId")
    java.util.Optional<Painting> findForReplay(@org.springframework.data.repository.query.Param("paintingId") Integer paintingId);
}
