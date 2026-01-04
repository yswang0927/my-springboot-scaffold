package codegen;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class TUserServiceImpl implements TUserService {

    private final TUserRepository tUserRepository;

    public TUserServiceImpl(TUserRepository repository) {
        this.tUserRepository = repository;
    }

    @Override
    public List<TUserEntity> findAll(Integer pageNo, Integer pageSize) {
        if (pageNo != null || pageSize != null) {
            int page = pageNo != null ? pageNo.intValue() : 0;
            int limit = pageSize != null ? pageSize.intValue() : 20;
            return this.tUserRepository.findAll(PageRequest.of(page, limit)).toList();
        }

        return this.tUserRepository.findAll();
    }

    @Override
    public Optional<TUserEntity> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return this.tUserRepository.findById(id);
    }

    @Override
    public TUserEntity save(TUserEntity tUserEntity) {
        if (tUserEntity == null) {
            return null;
        }
        return this.tUserRepository.save(tUserEntity);
    }

    @Override
    public boolean delete(Long id) {
        if (id == null) {
            return false;
        }
        this.tUserRepository.deleteById(id);
        return true;
    }

    @Override
    public TUserEntity update(Long id, TUserEntity updatedTUserEntity) {
        if (updatedTUserEntity == null) {
            return null;
        }

        Optional<TUserEntity> found = this.findById(id);
        if (found.isEmpty()) {
            return null;
        }

        updatedTUserEntity.setId(id);
        return this.tUserRepository.saveAndFlush(updatedTUserEntity);
    }

}
