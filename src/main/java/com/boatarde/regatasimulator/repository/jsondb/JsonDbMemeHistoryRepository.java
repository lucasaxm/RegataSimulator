package com.boatarde.regatasimulator.repository.jsondb;

import com.boatarde.regatasimulator.models.Meme;
import com.boatarde.regatasimulator.repository.MemeHistoryRepository;
import com.boatarde.regatasimulator.util.JsonDBUtils;
import io.jsondb.JsonDBTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="regata-simulator.database.engine",havingValue="jsondb",matchIfMissing=true)
public class JsonDbMemeHistoryRepository implements MemeHistoryRepository {
    private final JsonDBTemplate db;
    public JsonDbMemeHistoryRepository(JsonDBTemplate db) { this.db = db; }

    @Override
    public List<Meme> newestFirst() {
        return db.<Meme>findAll(Meme.class).stream().sorted(JsonDBUtils.getMemeComparator().reversed()).toList();
    }

    @Override
    public synchronized void recordDelivered(Meme meme) {
        // Insert before trimming so insertion failure does not discard existing history.
        db.insert(meme);
        List<Meme> history = newestFirst();
        for (Meme expired : history.stream().skip(1000).toList()) {
            if (db.remove(expired, Meme.class) == null) {
                throw new IllegalStateException("History retention failed");
            }
        }
    }
}