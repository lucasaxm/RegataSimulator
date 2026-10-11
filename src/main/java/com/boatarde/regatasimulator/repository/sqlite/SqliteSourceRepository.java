package com.boatarde.regatasimulator.repository.sqlite;

import com.boatarde.regatasimulator.application.ApplicationFailure;
import com.boatarde.regatasimulator.models.*;
import com.boatarde.regatasimulator.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

public class SqliteSourceRepository implements SourceRepository {
    private final SqliteStore store;
    private final SqliteCodec codec;
    public SqliteSourceRepository(SqliteStore store, ObjectMapper mapper) { this.store = store; codec = new SqliteCodec(mapper); }
    private record Filter(String sql, List<Object> args) { }
    private Filter filter(Criteria criteria) {
        StringBuilder sql = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (criteria.status() != null) { sql.append(" AND status=?"); args.add(criteria.status().name()); }
        if (criteria.authorId() != null) { sql.append(" AND author_id=?"); args.add(criteria.authorId()); }
        if (!criteria.descriptions().isEmpty()) {
            sql.append(" AND (");
            sql.append(String.join(" OR ", Collections.nCopies(criteria.descriptions().size(), "instr(description_key,?)>0")));
            sql.append(")");
            criteria.descriptions().forEach(name -> args.add(DescriptionKey.of(name)));
        }
        return new Filter(sql.toString(), args);
    }
    private Source row(java.sql.ResultSet row, int ignored) throws java.sql.SQLException {
        Source source = new Source(); codec.common(row, source); source.setDescription(row.getString("description")); return source;
    }
    @Override public List<Source> find(Criteria criteria) {
        Filter f = filter(criteria);
        return SqliteOperations.call(() -> store.jdbc().query("SELECT * FROM sources" + f.sql() + " ORDER BY coalesce(origin_date,0) DESC,id DESC", this::row, f.args().toArray()));
    }
    @Override public GalleryResponse<Source> page(Criteria criteria, int page, int perPage) {
        RepositoryPages.validate(page, perPage);
        Filter f = filter(criteria);
        return SqliteOperations.call(() -> store.transactions().execute(tx -> {
            int count = store.jdbc().queryForObject("SELECT count(*) FROM sources" + f.sql(), Integer.class, f.args().toArray());
            List<Object> args = new ArrayList<>(f.args()); args.add(perPage); args.add((long) (page - 1) * perPage);
            return new GalleryResponse<>(store.jdbc().query("SELECT * FROM sources" + f.sql() + " ORDER BY coalesce(origin_date,0) DESC,id DESC LIMIT ? OFFSET ?", this::row, args.toArray()), count);
        }));
    }
    @Override public Optional<Source> findById(UUID id) {
        return SqliteOperations.call(() -> store.jdbc().query("SELECT * FROM sources WHERE id=?", this::row, id.toString()).stream().findFirst());
    }
    @Override public void insertSubmission(Source source) {
        String key = DescriptionKey.of(source.getDescription());
        if (key != null && key.isEmpty()) throw new ApplicationFailure(ApplicationFailure.Kind.INVALID_INPUT, "Empty description");
        SqliteOperations.call(() -> store.jdbc().update("INSERT INTO sources(id,description,description_key,weight,status,message_json,author_id,chat_id,origin_date,preview_chat_id,preview_message_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            source.getId().toString(), source.getDescription(), key, source.getWeight(), source.getStatus().name(), codec.json(source.getMessage()),
            SqliteCodec.author(source.getMessage()), SqliteCodec.chat(source.getMessage()), SqliteCodec.date(source.getMessage()), source.getPreviewChatId(), source.getPreviewMessageId()));
    }
    @Override public void insertImported(List<Source> sources) {
        SqliteOperations.call(() -> store.transactions().execute(tx -> { sources.forEach(this::insertSubmission); return null; }));
    }
    @Override public boolean remove(Source source) { return SqliteOperations.call(() -> store.jdbc().update("DELETE FROM sources WHERE id=?", source.getId().toString()) == 1); }
    @Override public boolean decideReview(UUID id, Status status) {
        SqliteOperations.decision(status);
        return SqliteOperations.call(() -> store.jdbc().update("UPDATE sources SET status=?,preview_chat_id=NULL,preview_message_id=NULL WHERE id=? AND status='REVIEW'", status.name(), id.toString()) == 1);
    }
    @Override public boolean bindReviewPreview(UUID id, long chatId, int messageId) {
        SqliteOperations.binding(messageId);
        return SqliteOperations.call(() -> store.jdbc().update("UPDATE sources SET preview_chat_id=?,preview_message_id=? WHERE id=? AND status='REVIEW'", chatId, messageId, id.toString()) == 1);
    }
    @Override public boolean clearPreview(UUID id) { return SqliteOperations.call(() -> store.jdbc().update("UPDATE sources SET preview_chat_id=NULL,preview_message_id=NULL WHERE id=?", id.toString()) == 1); }
    @Override public void resetWeights(int weight) { SqliteOperations.call(() -> store.jdbc().update("UPDATE sources SET weight=?", weight)); }
    @Override public void decreaseWeight(UUID id) { SqliteOperations.call(() -> store.jdbc().update("UPDATE sources SET weight=max(1,weight-1) WHERE id=?", id.toString())); }
}