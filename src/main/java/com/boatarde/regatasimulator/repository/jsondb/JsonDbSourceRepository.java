package com.boatarde.regatasimulator.repository.jsondb;

import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.repository.SourceRepository;
import com.boatarde.regatasimulator.util.JsonDBUtils;
import io.jsondb.JsonDBTemplate;
import io.jsondb.query.Update;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JsonDbSourceRepository implements SourceRepository {
    private static final String BY_ID = "/.[id='%s']";
    private static final String PREVIEW_CHAT = "previewChatId";
    private static final String PREVIEW_MESSAGE = "previewMessageId";
    private final JsonDBTemplate db;

    public JsonDbSourceRepository(JsonDBTemplate db) { this.db = db; }

    @Override
    public List<Source> find(Criteria criteria) {
        String query = JsonDBUtils.jxQuery().withStatus(criteria.status()).withUserId(criteria.authorId()).build();
        List<Source> sources = db.find(query, Source.class);
        return sources.stream().filter(source -> criteria.descriptions().isEmpty()
            || source.getDescription() != null && criteria.descriptions().stream().anyMatch(description ->
                source.getDescription().toLowerCase(Locale.ROOT).contains(description.toLowerCase(Locale.ROOT))))
            .toList();
    }

    @Override public Optional<Source> findById(UUID id) { return Optional.ofNullable(db.findById(id, Source.class)); }
    @Override public void insertSubmission(Source source) { db.insert(source); }
    @Override public void insertImported(List<Source> sources) { db.insert(sources, Source.class); }
    @Override public boolean remove(Source source) { return db.remove(source, Source.class) != null; }

    @Override
    public boolean decideReview(UUID id, Status decision) {
        if (decision != Status.APPROVED && decision != Status.REJECTED) {
            throw new IllegalArgumentException("Decision must be APPROVED or REJECTED");
        }
        return db.findAndModify(reviewQuery(id), Update.update("status", decision)
            .set(PREVIEW_CHAT, null).set(PREVIEW_MESSAGE, null), Source.class) != null;
    }

    @Override
    public boolean bindReviewPreview(UUID id, long chatId, int messageId) {
        if (messageId <= 0) throw new IllegalArgumentException("Preview message must be positive");
        return db.findAndModify(reviewQuery(id), Update.update(PREVIEW_CHAT, chatId)
            .set(PREVIEW_MESSAGE, messageId), Source.class) != null;
    }

    @Override
    public boolean clearPreview(UUID id) {
        return db.findAndModify(BY_ID.formatted(id), Update.update(PREVIEW_CHAT, null)
            .set(PREVIEW_MESSAGE, null), Source.class) != null;
    }

    @Override
    public void resetWeights(int weight) {
        for (Source source : find(Criteria.all())) {
            db.findAndModify(BY_ID.formatted(source.getId()), Update.update("weight", weight), Source.class);
        }
    }

    @Override
    public synchronized void decreaseWeight(UUID id) {
        findById(id).ifPresent(source -> db.findAndModify(BY_ID.formatted(id),
            Update.update("weight", Math.max(1, source.getWeight() - 1)), Source.class));
    }

    private String reviewQuery(UUID id) { return "/.[id='%s' and status='REVIEW']".formatted(id); }
}