# 独立 LZO 参考数据

这三个二进制文件只供 JVM 测试使用，不会进入生产 APK。原始内容由 `MdictReaderTest.referenceVectors` 的固定种子生成规则定义，每组 65,536 字节，无第三方词库内容。

2026-10-12 使用官方 LZO 2.10 的 `lzo1x_1_compress` 生成，不读取或复制其解压实现。官方下载地址：https://www.oberhumer.com/opensource/lzo/download/lzo-2.10.tar.gz 。参考工具只安装于 `/tmp`，工程与运行时无 LZO 库依赖。

| 文件 | SHA-256 |
| --- | --- |
| lzo-2.10-0.bin | e1f0af25a6f666804a6da1f2299a78d918c2beefb96a903df4f25feed6dab4a5 |
| lzo-2.10-1.bin | f1f741d3de7b8eb7a21886147165c9410d5e417de944e4ba40988f83613fad7f |
| lzo-2.10-2.bin | 6e675f49e842132d52c9c888ab4a5e9640c83377ac66e5b972a5fc439598b235 |
