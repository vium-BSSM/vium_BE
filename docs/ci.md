# CI

`.github/workflows/ci.yml`은 `develop` 또는 `main` 대상 PR, 두 브랜치의 push,
수동 실행에서 운영 Docker 이미지를 빌드한다. GitHub 호스팅 Ubuntu 24.04
러너에서 EC2 t3와 같은 `linux/amd64` 아키텍처를 사용한다.

Dockerfile의 Java 21 빌드 단계가 `test bootJar`를 실행한다. 테스트가 실패하면
이미지 빌드와 CI도 실패한다. 매 실행마다 새 GitHub 호스팅 러너를 사용하고
외부 빌드 캐시를 복원하지 않으므로 테스트 빌드 단계가 실행된다.
Gradle 배포판과 의존성도 실행 간 공유되지 않는다. 현재 테스트 DB는 H2이며, 이 CI만으로 PostgreSQL
Flyway 마이그레이션이나 실제 RDS 연결을 검증한 것은 아니다.

이 단계에서는 이미지를 레지스트리에 게시하거나 EC2를 배포하지 않는다.
AWS 자격 증명 및 운영 DB/JWT 비밀값을 GitHub에 등록할 필요가 없다.

동일 PR의 이전 실행만 취소한다. push와 수동 실행은 실행 ID별 concurrency
그룹을 사용해 실행 중인 작업뿐 아니라 대기 중인 작업도 서로 대체하지 않는다.

## 첫 실행 확인

1. 작업 브랜치를 push하고 `develop` 대상으로 PR을 생성한다.
2. PR의 Checks에서 `Test and build image` 결과를 확인한다.
3. 실패하면 해당 job의 `Test and build production image` 로그를 확인한다.
4. 통과 후 브랜치 보호 규칙의 필수 검사로 `Test and build image`를 지정한다.
   워크플로 파일만 추가해서는 실패한 PR의 머지가 차단되지 않는다.

## 다음 단계

ECR 저장소, GitHub OIDC 역할, EC2 이미지 읽기 및 배포 권한을 준비한 뒤
`main`의 검증된 커밋을 배포하는 CD를 추가한다. 커밋 SHA로 이미지를 구분하고
health 실패 시 이전 이미지로 복구한다. DB 마이그레이션은 이미지 롤백만으로
되돌릴 수 없으므로 이전 앱과 호환되는 스키마 변경이 필요하다.

후속 개선: PostgreSQL 16에서 Flyway 및 prod 기동 검사, JUnit 보고서 업로드,
실행 간 빌드 캐시를 추가한다. 현재 실패 원인은 Docker 빌드 로그로 확인한다.
메모리 override의 버전 관리는 CI/CD 구축 시 실제 운영 설정을 보존하기 위한 범위다.
