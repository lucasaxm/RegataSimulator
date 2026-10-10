package com.boatarde.regatasimulator.repository.jsondb;

import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.repository.TemplateRepository;
import com.boatarde.regatasimulator.util.JsonDBUtils;
import io.jsondb.JsonDBTemplate;
import io.jsondb.query.Update;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JsonDbTemplateRepository implements TemplateRepository {
    private static final String BY_ID = "/.[id='%s']";
    private static final String PREVIEW_CHAT = "previewChatId";
    private static final String PREVIEW_MESSAGE = "previewMessageId";
    private final JsonDBTemplate db;

    public JsonDbTemplateRepository(JsonDBTemplate db) { this.db = db; }

    @Override
    public List<Template> find(Criteria criteria) {
        String query = JsonDBUtils.jxQuery().withStatus(criteria.status()).withUserId(criteria.authorId()).build();
        List<Template> templates = db.find(query, Template.class);
        return templates.stream().filter(template -> !criteria.singleAreaOnly()
            || template.getAreas() != null && template.getAreas().size() == 1).toList();
    }

    @Override public Optional<Template> findById(UUID id) { return Optional.ofNullable(db.findById(id, Template.class)); }
    @Override public void insertSubmission(Template template) { db.insert(template); }
    @Override public boolean remove(Template template) { return db.remove(template, Template.class) != null; }

    @Override
    public boolean decideReview(UUID id, Status decision) {
        if (decision != Status.APPROVED && decision != Status.REJECTED) {
            throw new IllegalArgumentException("Decision must be APPROVED or REJECTED");
        }
        return db.findAndModify(reviewQuery(id), Update.update("status", decision)
            .set(PREVIEW_CHAT, null).set(PREVIEW_MESSAGE, null), Template.class) != null;
    }

    @Override
    public boolean bindReviewPreview(UUID id, long chatId, int messageId) {
        if (messageId <= 0) throw new IllegalArgumentException("Preview message must be positive");
        return db.findAndModify(reviewQuery(id), Update.update(PREVIEW_CHAT, chatId)
            .set(PREVIEW_MESSAGE, messageId), Template.class) != null;
    }

    @Override
    public boolean clearPreview(UUID id) {
        return db.findAndModify(BY_ID.formatted(id), Update.update(PREVIEW_CHAT, null)
            .set(PREVIEW_MESSAGE, null), Template.class) != null;
    }

    @Override
    public void resetWeights(int weight) {
        for (Template template : find(Criteria.all())) {
            db.findAndModify(BY_ID.formatted(template.getId()), Update.update("weight", weight), Template.class);
        }
    }

    @Override
    public synchronized void decreaseWeight(UUID id) {
        findById(id).ifPresent(template -> db.findAndModify(BY_ID.formatted(id),
            Update.update("weight", Math.max(1, template.getWeight() - 1)), Template.class));
    }

    @Override
    public void initializeSourceIds() {
        for (Template template : find(Criteria.all())) {
            template.getAreas().forEach(area -> area.setSource(area.getIndex()));
            db.findAndModify(BY_ID.formatted(template.getId()),
                Update.update("areas", template.getAreas()), Template.class);
        }
    }

    private String reviewQuery(UUID id) { return "/.[id='%s' and status='REVIEW']".formatted(id); }
}