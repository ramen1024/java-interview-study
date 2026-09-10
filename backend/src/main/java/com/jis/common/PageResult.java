package com.jis.common;

import java.util.List;
import java.util.function.Function;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Getter;

/**
 * 分页结果。与持久层解耦之处在于只暴露必要字段，
 * 前端不必认识 MyBatis-Plus 的 {@code IPage} 结构。
 */
@Getter
public class PageResult<T> {

    private final List<T> records;
    private final long total;
    private final long page;
    private final long size;
    private final long pages;

    private PageResult(List<T> records, long total, long page, long size, long pages) {
        this.records = records;
        this.total = total;
        this.page = page;
        this.size = size;
        this.pages = pages;
    }

    public static <T> PageResult<T> of(IPage<T> source) {
        return new PageResult<>(
                source.getRecords(),
                source.getTotal(),
                source.getCurrent(),
                source.getSize(),
                source.getPages()
        );
    }

    /**
     * 分页转换：把实体分页映射为 VO 分页，保留分页元信息。
     */
    public static <E, T> PageResult<T> of(IPage<E> source, Function<E, T> mapper) {
        List<T> mapped = source.getRecords()
                .stream()
                .map(mapper)
                .toList();

        return new PageResult<>(
                mapped,
                source.getTotal(),
                source.getCurrent(),
                source.getSize(),
                source.getPages()
        );
    }
}
