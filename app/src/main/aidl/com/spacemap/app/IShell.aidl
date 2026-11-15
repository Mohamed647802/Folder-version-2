package com.spacemap.app;
interface IShell {
    void destroy() = 16777114;
    String[] list(String path) = 1;
    long size(String path) = 2;
    boolean delete(String path) = 3;
    boolean copy(String src, String dst) = 4;
}
