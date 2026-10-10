package com.boatarde.regatasimulator.repository;

import com.boatarde.regatasimulator.models.Author;
import java.util.List;

public interface AuthorRepository {
    void recordSubmitter(Author author);
    List<Author> findAll();
}