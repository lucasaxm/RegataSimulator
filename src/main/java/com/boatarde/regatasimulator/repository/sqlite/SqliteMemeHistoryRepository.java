package com.boatarde.regatasimulator.repository.sqlite;

import com.boatarde.regatasimulator.models.Meme;
import com.boatarde.regatasimulator.repository.MemeHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.telegram.telegrambots.meta.api.objects.Message;
import java.util.List;
import java.util.UUID;

public class SqliteMemeHistoryRepository implements MemeHistoryRepository {
    private final SqliteStore store;
    private final SqliteCodec codec;
    public SqliteMemeHistoryRepository(SqliteStore store, ObjectMapper mapper) { this.store = store; codec = new SqliteCodec(mapper); }
    private static String uuid(UUID id) { return id == null ? null : id.toString(); }
    @Override public List<Meme> newestFirst() {
        return SqliteOperations.call(() -> store.transactions().execute(tx -> store.jdbc().query("SELECT * FROM memes ORDER BY coalesce(origin_date,0) DESC,id DESC", (row, index) -> {
            String id = row.getString("id"); String template = row.getString("template_uuid");
            List<UUID> sources = row.getInt("source_ids_present") == 0 ? null : store.jdbc().query("SELECT source_uuid FROM meme_sources WHERE meme_id=? ORDER BY position",
                (source, position) -> source.getString(1) == null ? null : UUID.fromString(source.getString(1)), id);
            return Meme.builder().id(UUID.fromString(id)).templateId(template == null ? null : UUID.fromString(template)).sourceIds(sources)
                .message(codec.read(row.getString("message_json"), Message.class)).build();
        })));
    }
    /** Offline import preserves all rows; retention only runs on newly delivered publication. */
    public void importHistory(Meme meme) {
        store.jdbc().update("INSERT INTO memes(id,template_uuid,template_link,source_ids_present,message_json,origin_date) VALUES(?,?,(SELECT id FROM templates WHERE id=?),?,?,?)",
            uuid(meme.getId()), uuid(meme.getTemplateId()), uuid(meme.getTemplateId()), meme.getSourceIds() == null ? 0 : 1, codec.json(meme.getMessage()), SqliteCodec.date(meme.getMessage()));
        if (meme.getSourceIds() != null) {
            for (int i = 0; i < meme.getSourceIds().size(); i++) {
                String source = uuid(meme.getSourceIds().get(i));
                store.jdbc().update("INSERT INTO meme_sources(meme_id,position,source_uuid,source_link) VALUES(?,?,?,(SELECT id FROM sources WHERE id=?))", uuid(meme.getId()), i, source, source);
            }
        }
    }
    @Override public void recordDelivered(Meme meme) {
        SqliteOperations.call(() -> store.transactions().execute(tx -> {
            importHistory(meme);
            store.jdbc().update("DELETE FROM memes WHERE id IN (SELECT id FROM memes ORDER BY coalesce(origin_date,0) DESC,id DESC LIMIT -1 OFFSET 1000)");
            return null;
        }));
    }
}