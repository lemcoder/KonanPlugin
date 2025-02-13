For Android

After copy everything
from
~\AppData\Local\Android\Sdk\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\lib\clang\18
to
~\.konan\dependencies\llvm-16.0.0-x86_64-windows-essentials-56\lib\clang\16

the error ld.lld: error: cannot open 
~\.konan\dependencies\llvm-16.0.0-x86_64-windows-essentials-56\lib\clang\16\lib\linux\libclang_rt.builtins-arm-android.a: No such file or directory

ld.lld: error: unable to find library 
-l:libunwind.a

is gone