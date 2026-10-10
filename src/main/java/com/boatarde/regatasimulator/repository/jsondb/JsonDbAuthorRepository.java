package com.boatarde.regatasimulator.repository.jsondb;

import com.boatarde.regatasimulator.models.Author;
import com.boatarde.regatasimulator.repository.AuthorRepository;
import io.jsondb.JsonDBTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public class JsonDbAuthorRepository implements AuthorRepository {
    private final JsonDBTemplate db;
    public JsonDbAuthorRepository(JsonDBTemplate db) { this.db = db; }
    @Override public void recordSubmitter(Author author) { db.upsert(author); }
    @Override public List<Author> findAll() { return List.copyOf(db.findAll(Author.class)); }
}