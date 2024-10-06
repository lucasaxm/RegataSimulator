package com.boatarde.regatasimulator.models;

import lombok.Data;
import org.telegram.telegrambots.meta.api.objects.User;

@Data
public class Author {
    private Long id;
    private String firstName;
    private String lastName;
    private String userName;

    public Author(User user) {
        if (user == null) {
            return;
        }
        this.id = user.getId();
        this.firstName = user.getFirstName();
        this.lastName = user.getLastName();
        this.userName = user.getUserName();
    }
}
