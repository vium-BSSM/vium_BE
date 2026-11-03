# CI

`.github/workflows/ci.yml`은 `develop` 또는 `main` 대상 PR, 두 브랜치의 push,
수동 실행에서 운영 Docker 이미지를 빌드한다. GitHub 호스팅 Ubuntu 24.04
러너에서 EC2 t3와 같은 `linux/amd64` 아키텍처를 사용한다.

Dockerfile의 Java 21 빌드 단계가 `test bootJar`를 실행한다. 테스트가 실패하면
이미지 빌드와 CI도 실패한다. 매 실행마다 새 GitHub 호스팅 러너를 사용하고
외부 빌드 캐시를 복원하지 않으므로 테스트 빌드 단계가 실행된다.
이 Docker 빌드에서는 Gradle 배포판과 의존성도 실행 간 공유되지 않는다. 테스트 DB는 H2이며,
PostgreSQL Flyway·스키마 검증은 아래 별도 job이 수행한다. 실제 RDS 연결은 검증하지 않는다.

이 단계에서는 이미지를 레지스트리에 게시하거나 EC2를 배포하지 않는다.
AWS 자격 증명 및 운영 DB/JWT 비밀값을 GitHub에 등록할 필요가 없다.

동일 PR의 이전 실행만 취소한다. push와 수동 실행은 실행 ID별 concurrency
그룹을 사용해 실행 중인 작업뿐 아니라 대기 중인 작업도 서로 대체하지 않는다.

## PostgreSQL 마이그레이션 검사

`PostgreSQL migration check`는 기존 이미지 빌드 job과 병렬로 실행한다.
Java 21로 `bootJar`만 빌드하고, PostgreSQL 16 서비스 컨테이너의 빈 DB에
운영과 같은 `prod` 프로필과 Asia/Seoul 시간대로 앱을 띄운다.
이 job은 Gradle 캐시를 사용하며 H2 테스트를 다시 실행하지 않는다.

Flyway V1~최신 적용과 `ddl-auto: validate` 후 health가 `UP`인지 확인한다.
Flyway 이력의 실패 건수 0, 저장소 최신 버전 일치, repeatable dev 시드 미적용도 확인한다.
잘못된 마이그레이션이나 엔티티·스키마 불일치로 앱 기동이 실패하면 PR 체크도 실패한다.
실패 원인은 이 job의 `Show app log` step에서 확인한다.

매번 빈 DB에서 시작하므로 운영 DB에 이미 적용된 마이그레이션의 수정에 따른
checksum 불일치, 운영 데이터에 의존하는 마이그레이션 실패, RDS SSL 연결 설정은
검증하지 못한다. 이미 적용된 마이그레이션을 수정하지 않는 규칙(CLAUDE.md 7.5)은
계속 리뷰에서 지킨다.

## 첫 실행 확인

1. 작업 브랜치를 push하고 `develop` 대상으로 PR을 생성한다.
2. PR의 Checks에서 `Test and build image`와 `PostgreSQL migration check` 결과를 확인한다.
3. 이미지 빌드 실패는 `Test and build production image`, PostgreSQL 검사 실패는
   `Show app log` 로그를 확인한다.
4. 통과 후 `develop`·`main` 브랜치 보호 규칙의 필수 검사로
   `Test and build image`와 `PostgreSQL migration check`를 지정한다.
   새 체크는 PR에서 한 번 실행된 뒤 목록에 나타난다.
   워크플로 파일만 추가해서는 실패한 PR의 머지가 차단되지 않는다.

## CD

[CD 안내](cd.md)의 별도 워크플로가 main에서 테스트/빌드 후 ECR 게시 및
SSM 배포를 수행한다. CI에서는 배포 스크립트의 문법과 모의 테스트도 실행한다.

후속 개선: JUnit 보고서 업로드와 Docker 빌드의 실행 간 빌드 캐시를 추가한다.
메모리 override의 버전 관리는 CI/CD 구축 시 실제 운영 설정을 보존하기 위한 범위다.
