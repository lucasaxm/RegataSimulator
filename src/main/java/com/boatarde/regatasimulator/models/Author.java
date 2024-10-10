package com.boatarde.regatasimulator.models;

import io.jsondb.annotation.Document;
import io.jsondb.annotation.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.telegram.telegrambots.meta.api.objects.User;

@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
@Document(collection = "users", schemaVersion = "1.0")
public class Author {
    @Id
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
