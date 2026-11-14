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

## Dependabot

`.github/dependabot.yml`로 기본 브랜치 develop의 의존성 버전 업데이트를 받는다.
설정이 기본 브랜치에 머지되면 첫 업데이트 작업이 실행되고 이후 아래 일정으로 검사한다.

| 생태계 | 대상 | 일정(Asia/Seoul) | 열린 버전 PR 한도 |
|---|---|---|---|
| `github-actions` | `.github/workflows`의 `uses:` | 매월 1일 09:00 | 5 |
| `gradle` | `build.gradle`의 플러그인·AWS BOM 버전과 Gradle 래퍼 | 매월 1일 09:30 | 3 |

Actions minor/patch는 `github-actions` 그룹으로 묶고 메이저는 액션별 PR로 받는다.
Gradle은 먼저 Spring Boot·dependency-management 플러그인을 `spring-boot` 그룹으로,
나머지 버전 선언 의존성(현재 AWS SDK BOM)을 `libraries` 그룹으로 묶는다.
여러 그룹에 일치하면 앞의 그룹을 사용한다. `gradle-wrapper`는 libraries에서
제외해 개별 PR로 받는다. Gradle 메이저는 모두 무시하며 별도 이슈로 계획한다.
Spring Boot minor는 업데이트 대상이므로 릴리스 노트를 확인한다.
밀린 PR이 한도에 도달하면 다음 버전 PR이 열리지 않는다. 보안 PR은 이 한도와
별도로 최대 10개이며, 보안 업데이트용 그룹은 설정하지 않는다.

Docker 베이스 이미지는 digest 없이 변하는 Temurin 태그를 사용해 새 빌드에
패치를 반영한다. Java·OS 전환과 digest 고정은 별도 작업이므로 `docker` 생태계는
제외한다. CI의 `postgres:16-alpine` 서비스 이미지도 Actions 업데이트 대상이
아니며 운영 RDS 버전과 맞춰 별도로 변경한다.

`target-branch`는 기본 브랜치가 이미 develop이라 생략한다. 명시하면 해당 생태계의
설정이 보안 업데이트에는 적용되지 않는다. 기본 브랜치 변경 시 이 판단도 재검토한다.
`labels`는 지정하지 않아 기본 `dependencies`와 `github_actions`/`java` 레이블을
자동 생성·부착한다. 커밋과 PR 제목은 `chore: bump ...`이며 이슈 번호와 `Closes #`는
생략한다. Dependabot PR을 위한 이슈는 만들지 않는다.
옵션의 의미는 [GitHub Dependabot 옵션 문서](https://docs.github.com/en/code-security/reference/supply-chain-security/dependabot-options-reference)를 따른다.

### 액션 SHA와 래퍼 다운로드

새 액션도 `uses: owner/repo@<40자리 커밋 SHA> # vX.Y.Z` 형식으로 고정한다.
같은 줄의 전체 버전 주석은 Dependabot이 SHA와 함께 갱신한다. annotated tag
객체 SHA는 사용하지 않는다. `git ls-remote --tags`에서 annotated tag의 `^{}`
줄이 가리키는 커밋을 사용하고, lightweight tag는 태그 줄의 커밋을 사용한다.

래퍼는 `networkTimeout=30000`, `retries=3`, `retryBackOffMs=1000`을 사용한다.
Docker 빌드와 새 래퍼 버전의 첫 CI는 배포판을 다시 받으므로 일시적인 다운로드
장애로 실패하지 않도록 30초 타임아웃·3회 재시도·1초 백오프를 둔다.
Dependabot의 래퍼 갱신은 이 값을 보존한다. 배포판 버전과 체크섬 외 설정이
유지되는지 래퍼 PR에서 확인한다. Maven Central 다운로드 타임아웃은 별도 설정이다.

### 리뷰와 머지

자동 머지는 하지 않는다. `Test and build image`, `PostgreSQL migration check`가
모두 통과한 뒤 사용자가 PR 본문의 릴리스 노트·변경 로그를 확인하고 머지한다.
Dependabot PR은 Actions secrets 없이 제한된 토큰으로 실행된다. 현재 CI는
secrets와 repository variables가 필요 없어 일반 PR과 같은 검사를 수행한다.

| PR | 확인할 내용 |
|---|---|
| Actions minor/patch 그룹 | 입력·기본값 변경 여부 |
| Actions 메이저 | 입력·기본값, `runs.using`, BREAKING CHANGES. CD 액션이면 릴리스 후 [CD 확인](cd.md#준비된-aws-설정)까지 수행 |
| `spring-boot` 그룹 | Spring Boot 릴리스 노트(특히 minor). PostgreSQL job의 Flyway·Hibernate 검증 결과 |
| `libraries` 그룹 | AWS SDK 등 라이브러리 변경 내용과 CI 통과 |
| `gradle-wrapper` | properties의 `distributionUrl`과 필요 시 `distributionSha256Sum` 변경, 타임아웃·재시도 값 유지. jar와 실행 스크립트는 Gradle wrapper 작업의 생성물 |
| 보안 업데이트 | alert의 영향 범위. 메이저가 필요한 수정은 ignore로 PR이 없을 수 있어 별도 이슈로 대응 |

Dependabot PR에 직접 커밋하지 않는다. 직접 커밋하면 자동 rebase가 중단되므로,
수정이 필요하면 PR을 닫고 이슈를 만들어 작업한다. 불필요한 업데이트는
`@dependabot ignore this major version` 또는 `@dependabot ignore this dependency`
댓글로 제외하고 이유를 이 절이나 dependabot.yml의 ignore 설정에 기록한다.

### 보안 설정과 머지 후 확인

버전 업데이트 설정만으로 Gradle 취약점 alert이 생기지는 않는다. 머지 후 사용자가
저장소 Settings → Security and quality → Advanced Security에서 다음을 설정한다.

1. Dependency graph 활성화를 확인하고 Automatic dependency submission을 Enabled로 설정한다.
2. Dependabot alerts와 Dependabot security updates를 활성화한다. Grouped security updates는 켜지 않는다.
3. Actions의 `Automatic Dependency Submission (Gradle)` 성공과 Insights → Dependency graph → Dependencies의 Maven 패키지를 확인한다.
4. Security → Dependabot에서 alert의 영향 범위를 확인한다.

자동 제출은 GitHub이 관리하는 워크플로다. 실패하면 원인을 이 문서에 남기고
버전 업데이트를 우선 운영한다. 직접 dependency-submission 워크플로를 추가하는
작업은 별도 이슈로 다룬다. 설정 절차는
[자동 의존성 제출 문서](https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/configuring-automatic-dependency-submission-for-your-repository)를 참고한다.
BOM이 관리하는 전이 의존성의 취약점은 alert으로 확인하지만 Dependabot이 개별
보안 PR로 수정하지 못한다. Spring Boot/BOM 업데이트를 앞당기거나 긴급한 버전
재정의를 별도 이슈로 처리한다.

머지 후 Insights → Dependency graph → Dependabot에서 Actions와 Gradle 두 작업의
Last checked와 오류 여부를 확인한다. 필요하면 Recent update jobs → Check for updates를
실행한다. 첫 PR의 대상이 develop인지, 레이블·제목·SHA 및 버전 주석·래퍼 재시도
설정이 위 규칙과 일치하는지 확인한다. PR 단계의 YAML 파싱은 문법 검사일 뿐이며
실제 Dependabot 설정 처리는 이 업데이트 작업에서 검증한다.

## CD

[CD 안내](cd.md)의 별도 워크플로가 main에서 테스트/빌드 후 ECR 게시 및
SSM 배포를 수행한다. CI에서는 배포 스크립트의 문법과 모의 테스트도 실행한다.

후속 개선: JUnit 보고서 업로드와 Docker 빌드의 실행 간 빌드 캐시를 추가한다.
메모리 override의 버전 관리는 CI/CD 구축 시 실제 운영 설정을 보존하기 위한 범위다.
