#!/bin/bash

rm -r build64
mkdir build64
cd build64 || exit 1
cmake .. -DCMAKE_TOOLCHAIN_FILE=../cross-arm64.cmake
make -j8

cd .. || exit 1

rm -r build
mkdir build
cd build || exit 1
cmake .. -DCMAKE_TOOLCHAIN_FILE=../cross-armhf.cmake
make -j8