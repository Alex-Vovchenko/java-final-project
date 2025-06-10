package searchengine.model;

import javax.persistence.*;
import javax.validation.constraints.NotNull;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@Entity
@Getter
@Setter
@Table(name = "page", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"site_id", "path"})
})
public class Page {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "site_id", nullable = false)
    private Site site;

    @Column(name = "path", nullable = false)
    private String path;

    @NotNull
    @Column(name = "code", nullable = false)
    private int code;

    @NotNull
    @Column(columnDefinition = "MEDIUMTEXT", nullable = false)
    private String content;
}