package com.boatarde.regatasimulator.repository;

import com.boatarde.regatasimulator.models.Meme;
import java.util.List;

public interface MemeHistoryRepository {
    List<Meme> newestFirst();

    /** Serialize cap enforcement within this adapter; not a database/transport transaction. */
    void recordDelivered(Meme meme);
}