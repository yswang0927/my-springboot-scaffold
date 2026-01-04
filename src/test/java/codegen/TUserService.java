package codegen;

import java.util.*;

public interface TUserService {
    List<TUserEntity> findAll(Integer pageNo, Integer pageSize);

    Optional<TUserEntity> findById(Long id);

    TUserEntity save(TUserEntity tUserEntity);

    TUserEntity update(Long id, TUserEntity updatedTUserEntity);

    boolean delete(Long id);
}
