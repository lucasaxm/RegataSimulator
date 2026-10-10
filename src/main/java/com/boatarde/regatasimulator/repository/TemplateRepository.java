package com.boatarde.regatasimulator.repository;

import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.models.Template;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TemplateRepository {
    record Criteria(Status status, Long authorId, boolean singleAreaOnly) {
        public static Criteria all() { return new Criteria(null, null, false); }
    }

    List<Template> find(Criteria criteria);
    Optional<Template> findById(UUID id);
    void insertSubmission(Template template);
    boolean remove(Template template);
    boolean decideReview(UUID id, Status decision);
    boolean bindReviewPreview(UUID id, long chatId, int messageId);
    boolean clearPreview(UUID id);
    void resetWeights(int weight);
    void decreaseWeight(UUID id);
    void initializeSourceIds();
}