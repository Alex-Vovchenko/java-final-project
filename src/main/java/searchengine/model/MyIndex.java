package searchengine.model;

import com.sun.istack.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;

@NoArgsConstructor
@Entity
@Getter
@Setter
@Table(name = "My_index")
public class MyIndex {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "page_id", nullable = false)
    @NotNull
    private Page page;

    @ManyToOne
    @JoinColumn(name = "lemma_id", nullable = false)
    @NotNull
    private Lemma lemma;

    @NotNull
    @Column(name = "search_rank", nullable = false)
    private float rank;
}
