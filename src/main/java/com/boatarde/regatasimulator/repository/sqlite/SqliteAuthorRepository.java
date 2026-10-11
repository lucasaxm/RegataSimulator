package com.boatarde.regatasimulator.repository.sqlite;

import com.boatarde.regatasimulator.models.Author;
import com.boatarde.regatasimulator.repository.AuthorRepository;
import java.util.List;

public class SqliteAuthorRepository implements AuthorRepository {
    private final SqliteStore store;
    public SqliteAuthorRepository(SqliteStore store) { this.store = store; }
    @Override public void recordSubmitter(Author author) {
        if (author == null || author.getId() == null) throw new IllegalArgumentException("Author ID required");
        SqliteOperations.call(() -> store.jdbc().update("INSERT INTO users(id,first_name,last_name,user_name) VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET first_name=excluded.first_name,last_name=excluded.last_name,user_name=excluded.user_name",
            author.getId(), author.getFirstName(), author.getLastName(), author.getUserName()));
    }
    @Override public List<Author> findAll() {
        return SqliteOperations.call(() -> store.jdbc().query("SELECT * FROM users ORDER BY id", (row, index) -> Author.builder().id(row.getLong("id"))
            .firstName(row.getString("first_name")).lastName(row.getString("last_name")).userName(row.getString("user_name")).build()));
    }
}