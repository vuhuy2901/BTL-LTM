package com.nhom8.server.model;

import jakarta.persistence.*;
import java.io.Serializable;

/**
 * Thực thể lưu trữ thông tin bức tranh trong một trận đấu.
 * Mỗi bức tranh thuộc về một trận đấu cụ thể, do một người chơi vẽ,
 * gắn liền với một chủ đề riêng biệt.
 */
@Entity
@Table(name = "paintings")
public class Painting implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "painting_id")
    private Integer paintingId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "match_id", nullable = false)
    private Match match;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "painter_id", nullable = false)
    private User painter;

    @Column(nullable = false, length = 100)
    private String topic;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "topic_entry_id")
    private TopicEntry topicEntry;

    @Column(name = "round_number")
    private Integer roundNumber;

    @Column(name = "turn_number")
    private Integer turnNumber;

    @Lob
    @Column(name = "stroke_data", columnDefinition = "LONGTEXT")
    private String strokeData;

    @Lob
    @Column(name = "result_data", columnDefinition = "LONGTEXT")
    private String resultData;

    public Painting() {}

    public Painting(Match match, User painter, String topic) {
        this.match = match;
        this.painter = painter;
        this.topic = topic;
    }

    public Integer getPaintingId() { return paintingId; }
    public void setPaintingId(Integer paintingId) { this.paintingId = paintingId; }
    public Match getMatch() { return match; }
    public void setMatch(Match match) { this.match = match; }
    public User getPainter() { return painter; }
    public void setPainter(User painter) { this.painter = painter; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public TopicEntry getTopicEntry() { return topicEntry; }
    public void setTopicEntry(TopicEntry topicEntry) { this.topicEntry = topicEntry; }
    public Integer getRoundNumber() { return roundNumber; }
    public void setRoundNumber(Integer roundNumber) { this.roundNumber = roundNumber; }
    public Integer getTurnNumber() { return turnNumber; }
    public void setTurnNumber(Integer turnNumber) { this.turnNumber = turnNumber; }
    public String getStrokeData() { return strokeData; }
    public void setStrokeData(String strokeData) { this.strokeData = strokeData; }
    public String getResultData() { return resultData; }
    public void setResultData(String resultData) { this.resultData = resultData; }

    @Override
    public String toString() {
        return "Painting{paintingId=" + paintingId + ", painterId=" + (painter != null ? painter.getUserId() : "null") +
                ", topic='" + topic + "'}";
    }
}
