package codegen;

import java.util.*;

import com.myweb.common.ApiResult;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class TUserController {

    private final TUserService tUserService;

    public TUserController(TUserService tUserService) {
        this.tUserService = tUserService;
    }

    /**
     * 分页查询信息列表。
     * <p>请求方式：</p>
     * <pre>
     * GET /api/v1/tUsers
     * GET /api/v1/tUsers?page=0
     * GET /api/v1/tUsers?page=0&pageSize=10
     * </pre>
     *
     * @param page 可选】页码，【从 0 开始
     * @param pageSize 【可选】每页数据量
     * @return 数据列表
     */
    @GetMapping("/tUsers")
    public ApiResult<List<TUserEntity>> findAll(@RequestParam(name="page", required = false) Integer page,
                                                @RequestParam(name="pageSize", required = false) Integer pageSize) {
        return ApiResult.success(this.tUserService.findAll(page, pageSize));
    }

    /**
     * 根据ID查询记录信息。
     * <p>请求方式：</p>
     * <pre>
     * GET /api/v1/tUsers/123
     * </pre>
     *
     * @param id 记录主键ID
     * @return 满足条件的记录信息
     */
    @GetMapping("/tUsers/{id}")
    public ApiResult<TUserEntity> findById(@PathVariable("id") Long id) {
        Optional<TUserEntity> res = this.tUserService.findById(id);
        if (res.isPresent()) {
            return ApiResult.success(res.get());
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "请求的记录找不到");
    }

    /**
     * 新增记录。
     * <p>请求方式：</p>
     * <pre>
     * POST /api/v1/tUsers
     * headers: Content-Type=application/json
     * payload: {"key1":"value1", "...":"..."}
     * </pre>
     *
     * @param tUserEntity 记录信息
     * @return 新增成功的记录信息
     */
    @PostMapping(value="/tUsers", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<TUserEntity> save(@RequestBody TUserEntity tUserEntity) {
        TUserEntity saved = null;
        try {
            saved = this.tUserService.save(tUserEntity);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "新增失败");
        }

        if (saved != null) {
            return ApiResult.success(saved);
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的新增数据");
    }

    /**
     * 更新记录信息。
     * <p>请求方式：</p>
     * <pre>
     * POST /api/v1/tUsers/123
     * headers: Content-Type=application/json
     * payload: {"key1":"value1", "...":"..."}
     * </pre>
     *
     * @param id 待更新的记录ID
     * @param tUserEntity 记录信息
     * @return 更新成功的记录信息
     */
    @PutMapping(value="/tUsers/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult<TUserEntity> update(@PathVariable("id") Long id, @RequestBody TUserEntity tUserEntity) {
        TUserEntity updated = null;
        try {
            updated = this.tUserService.update(id, tUserEntity);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "更新记录失败");
        }

        if (updated != null) {
            return ApiResult.success(updated);
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的更新数据");
    }

    /**
     * 删除指定ID的记录信息
     * <p>请求方式：</p>
     * <pre>
     * DELETE /api/v1/tUsers/123
     * </pre>
     *
     * @param id 待删除的记录ID
     * @return 删除是否成功
     */
    @DeleteMapping("/tUsers/{id}")
    public ApiResult<Boolean> deleteById(@PathVariable("id") Long id) {
        boolean res = false;
        try {
            res = this.tUserService.delete(id);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "删除记录失败");
        }

        if (res) {
            return ApiResult.success(true);
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的删除数据");
    }

}
