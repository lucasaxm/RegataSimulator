package com.boatarde.regatasimulator.repository;

import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Operations required by submission, selection, galleries and review; not generic CRUD. */
public interface SourceRepository {
    record Criteria(Status status, Long authorId, List<String> descriptions) {
        public Criteria {
            descriptions = descriptions == null ? List.of() : List.copyOf(descriptions);
        }
        public static Criteria all() { return new Criteria(null, null, List.of()); }
    }

    List<Source> find(Criteria criteria);
    default com.boatarde.regatasimulator.models.GalleryResponse<Source> page(Criteria criteria, int page, int perPage) {
        return RepositoryPages.of(find(criteria), page, perPage);
    }
    Optional<Source> findById(UUID id);
    void insertSubmission(Source source);
    void insertImported(List<Source> sources);
    boolean remove(Source source);
    boolean decideReview(UUID id, Status decision);
    boolean bindReviewPreview(UUID id, long chatId, int messageId);
    boolean clearPreview(UUID id);
    void resetWeights(int weight);
    void decreaseWeight(UUID id);
}