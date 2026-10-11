package com.boatarde.regatasimulator.repository.sqlite;

import com.boatarde.regatasimulator.application.ApplicationFailure;
import com.boatarde.regatasimulator.models.*;
import com.boatarde.regatasimulator.repository.*;
import com.boatarde.regatasimulator.util.MediaValidation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;

public class SqliteTemplateRepository implements TemplateRepository {
    private final SqliteStore store;
    private final SqliteCodec codec;
    public SqliteTemplateRepository(SqliteStore store, ObjectMapper mapper) { this.store = store; codec = new SqliteCodec(mapper); }
    private record Filter(String sql, List<Object> args) { }
    private Filter filter(Criteria criteria) {
        StringBuilder sql = new StringBuilder(" WHERE 1=1"); List<Object> args = new ArrayList<>();
        if (criteria.status() != null) { sql.append(" AND status=?"); args.add(criteria.status().name()); }
        if (criteria.authorId() != null) { sql.append(" AND author_id=?"); args.add(criteria.authorId()); }
        if (criteria.singleAreaOnly()) sql.append(" AND json_array_length(areas_json)=1");
        return new Filter(sql.toString(), args);
    }
    private Template row(java.sql.ResultSet row, int ignored) throws java.sql.SQLException {
        Template template = new Template(); codec.common(row, template); template.setAreas(codec.areas(row.getString("areas_json"))); return template;
    }
    @Override public List<Template> find(Criteria criteria) {
        Filter f = filter(criteria);
        return SqliteOperations.call(() -> store.jdbc().query("SELECT * FROM templates" + f.sql() + " ORDER BY coalesce(origin_date,0) DESC,id DESC", this::row, f.args().toArray()));
    }
    @Override public GalleryResponse<Template> page(Criteria criteria, int page, int perPage) {
        RepositoryPages.validate(page, perPage); Filter f = filter(criteria);
        return SqliteOperations.call(() -> store.transactions().execute(tx -> {
            int count = store.jdbc().queryForObject("SELECT count(*) FROM templates" + f.sql(), Integer.class, f.args().toArray());
            List<Object> args = new ArrayList<>(f.args()); args.add(perPage); args.add((long) (page-1)*perPage);
            return new GalleryResponse<>(store.jdbc().query("SELECT * FROM templates" + f.sql() + " ORDER BY coalesce(origin_date,0) DESC,id DESC LIMIT ? OFFSET ?", this::row, args.toArray()), count);
        }));
    }
    @Override public Optional<Template> findById(UUID id) { return SqliteOperations.call(() -> store.jdbc().query("SELECT * FROM templates WHERE id=?", this::row, id.toString()).stream().findFirst()); }
    @Override public void insertSubmission(Template template) {
        validate(template.getAreas());
        SqliteOperations.call(() -> store.jdbc().update("INSERT INTO templates(id,areas_json,weight,status,message_json,author_id,chat_id,origin_date,preview_chat_id,preview_message_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
            template.getId().toString(), codec.json(template.getAreas()), template.getWeight(), template.getStatus().name(), codec.json(template.getMessage()),
            SqliteCodec.author(template.getMessage()), SqliteCodec.chat(template.getMessage()), SqliteCodec.date(template.getMessage()), template.getPreviewChatId(), template.getPreviewMessageId()));
    }
    private void validate(List<TemplateArea> areas) {
        try { MediaValidation.geometry(areas, null); }
        catch (IOException | NullPointerException e) { throw new ApplicationFailure(ApplicationFailure.Kind.INVALID_INPUT, "Invalid geometry", e); }
    }
    @Override public boolean remove(Template template) { return SqliteOperations.call(() -> store.jdbc().update("DELETE FROM templates WHERE id=?", template.getId().toString()) == 1); }
    @Override public boolean decideReview(UUID id, Status status) {
        SqliteOperations.decision(status);
        return SqliteOperations.call(() -> store.jdbc().update("UPDATE templates SET status=?,preview_chat_id=NULL,preview_message_id=NULL WHERE id=? AND status='REVIEW'", status.name(), id.toString()) == 1);
    }
    @Override public boolean bindReviewPreview(UUID id, long chatId, int messageId) {
        SqliteOperations.binding(messageId);
        return SqliteOperations.call(() -> store.jdbc().update("UPDATE templates SET preview_chat_id=?,preview_message_id=? WHERE id=? AND status='REVIEW'", chatId, messageId, id.toString()) == 1);
    }
    @Override public boolean clearPreview(UUID id) { return SqliteOperations.call(() -> store.jdbc().update("UPDATE templates SET preview_chat_id=NULL,preview_message_id=NULL WHERE id=?", id.toString()) == 1); }
    @Override public void resetWeights(int weight) { SqliteOperations.call(() -> store.jdbc().update("UPDATE templates SET weight=?", weight)); }
    @Override public void decreaseWeight(UUID id) { SqliteOperations.call(() -> store.jdbc().update("UPDATE templates SET weight=max(1,weight-1) WHERE id=?", id.toString())); }
    @Override public void initializeSourceIds() {
        SqliteOperations.call(() -> store.transactions().execute(tx -> {
            for (Template template : find(Criteria.all())) {
                template.getAreas().forEach(area -> area.setSource(area.getIndex())); validate(template.getAreas());
                store.jdbc().update("UPDATE templates SET areas_json=? WHERE id=?", codec.json(template.getAreas()), template.getId().toString());
            }
            return null;
        }));
    }
}