package codegen;

import java.sql.*;
import jakarta.persistence.*;

@Entity
@Table(name="t_user", schema = "public")
public class TUserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name="id")
    private Long id;
    
    
    @Column(name="name")
    private String name;
    
    
    @Column(name="age")
    private Integer age;
    
    
    @Column(name="email")
    private String email;

    public TUserEntity() {}

    public Long getId() {
        return this.id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getAge() {
        return this.age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }

    public String getEmail() {
        return this.email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

}