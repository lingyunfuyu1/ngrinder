# Learning Notes for nGrinder

**Markdown**
- http://wowubuntu.com/markdown/
- https://coding.net/help/doc/project/markdown.html

**EditorConfig**
- https://github.com/editorconfig/editorconfig/wiki
- https://www.jianshu.com/p/712cea0ef70e
- http://www.alloyteam.com/2014/12/editor-config/

**gitignore**
- https://git-scm.com/docs/gitignore

**Spring**
- http://www.importnew.com/14751.html
- http://blog.csdn.net/it_man/article/details/4402245
- https://www.cnblogs.com/ITtangtang/p/3978349.html

**性能平台**
- 阿里云 https://help.aliyun.com/product/29260.html?spm=a2c4g.750001.5.11.v0RtLT
- 腾讯云 http://wetest.qq.com/help/documentation/10255.html
- 压测宝 http://www.yacebao.com/

**Shell**
- Shell特殊变量：

变量 | 含义
:----------- | :-----------
- | :-: | -: 
$0 | 当前脚本的文件名
$n	传递给脚本或函数的参数。n 是一个数字，表示第几个参数。例如，第一个参数是$1，第二个参数是$2。
$#	传递给脚本或函数的参数个数。
$*	传递给脚本或函数的所有参数。
$@	传递给脚本或函数的所有参数。被双引号(" ")包含时，与 $* 稍有不同，下面将会讲到。
$?	上个命令的退出状态，或函数的返回值。
$$	当前Shell进程ID。对于 Shell 脚本，就是这些脚本所在的进程ID。


**关于Safe file distribution mode**
由2个参数共同控制，
controller.safe_dist-是否开启安全文件分发模式
controller.safe_dist_threshold-文件大小超过该值，强制开启安全文件分发模式
另外，已经创建的测试是安全或者非安全，复制并运行会保持旧的文件分发模式，所以改配置后需要重新创建测试。（需要确认）


**Dev Document**
https://github.com/naver/ngrinder/wiki/Dev-Document
- Vuser Test Result 
https://github.com/naver/ngrinder/wiki/Vuser-Test-Result
使用Groovy编写脚本比使用Jython编写脚本能提供更大的虚拟用户数。
2Core4gRAM VM (with 3.4Gram free)可以提供最大5000个虚拟用户/每个agent

- Naming Convention
https://github.com/naver/ngrinder/wiki/naming-convention
- How To Build nGrinder from scratch using Maven
https://github.com/naver/ngrinder/wiki/How-To-Build-nGrinder-from-scratch-using-Maven
- Java Implementation for Google Analytics Measurement Protocol
https://github.com/naver/ngrinder/wiki/Java-Implementation-for-Google-Analytics-Measurement-Protocol
- Coding Convention
https://github.com/naver/ngrinder/wiki/Coding-Convention
- How to refer nGrinder Home
https://github.com/naver/ngrinder/wiki/How-to-refer-nGrinder-Home
- Spring in nGrinder
https://github.com/naver/ngrinder/wiki/Spring-in-nGrinder
Spring Data
Spring Cache
Spring Task

- How to create dynamic queries in SpringData
https://github.com/naver/ngrinder/wiki/How-to-create-dynamic-queries-in-SpringData
- How to develop plugin
https://github.com/naver/ngrinder/wiki/How-to-develop-plugin
- nGrinder test execution performance test
https://github.com/naver/ngrinder/wiki/nGrinder-test-execution-performance-test
- nGrinder git branching, version policy
https://github.com/naver/ngrinder/wiki/nGrinder-git-branching%2C-version-policy
- Contributors
https://github.com/naver/ngrinder/wiki/Contributors





http://blademastercoder.github.io/2015/01/29/java-Serializable.html
