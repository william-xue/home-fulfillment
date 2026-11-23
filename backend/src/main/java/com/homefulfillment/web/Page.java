package com.homefulfillment.web;

import java.util.List;

public record Page<T>(List<T> items, long total, int page, int size) {
    public record Window(int page, int size) {
        public static Window of(int page, int size) {
            if (page<1 || size<1 || size>100) throw ApiProblem.invalid("页码至少为 1，页大小为 1–100");
            return new Window(page,size);
        }
        public long offset() { return (long)(page-1)*size; }
        public <T> Page<T> result(List<T> items, long total) { return new Page<>(items,total,page,size); }
    }
}
