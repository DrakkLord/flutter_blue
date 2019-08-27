#!/bin/bash

# check protoc
protoc_path=$(which protoc)
if [ ! -f "$protoc_path" ];  then
    echo -e "\033[31mprotoc no found, please make sure it's available on path!\033[0m\n"
    exit 1
fi

# check protoc-gen-dart
protoc_dart_path=$(which protoc-gen-dart)
if [ ! -f "$protoc_dart_path" ];  then
    echo -e "\033[31mprotoc-gen-dart no found, please make sure it's available on path!\033[0m\n"
    exit 1
fi

# run protoc
echo -e "\033[92mprotoc >> objc\033[0m\n"
$protoc_path --objc_out=../ios/gen ./flutterblue.proto
if [ $? -ne 0 ]
then
  echo -e "\033[31mprotoc >> objc, failed\033[0m\n"
  exit 1
fi

echo -e "\033[92mprotoc >> dart\033[0m\n"
$protoc_path --dart_out=../lib/gen ./flutterblue.proto
if [ $? -ne 0 ]
then
  echo -e "\033[31mprotoc >> dart, failed\033[0m\n"
  exit 1
fi

echo -e "\033[92mDONE\033[0m\n"
