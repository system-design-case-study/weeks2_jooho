#!/bin/bash

until cqlsh cassandra 9042 -e 'describe cluster' > /dev/null 2>&1; do
  echo "Cassandra 연결 대기 중..."
  sleep 5
done

echo "Cassandra 연결 성공. 스키마 초기화 실행..."
cqlsh cassandra 9042 -f /scripts/init.cql
echo "Cassandra 스키마 초기화 완료."
