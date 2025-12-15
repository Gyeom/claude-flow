---
description: Add tests for a specific component
---

# Add Tests

컴포넌트에 대한 테스트를 추가합니다.

## 사용법

`/add-test <component-path>`

예시:
- `/add-test routing/KoreanOptimizedRouter`
- `/add-test plugin/PluginRegistry`
- `/add-test ratelimit/AdvancedRateLimiter`

## 테스트 작성 규칙

1. **테스트 프레임워크**: Kotest (DescribeSpec 스타일)
2. **위치**: `src/test/kotlin/` 하위 동일 패키지
3. **네이밍**: `{ClassName}Test.kt`

## 테스트 템플릿

```kotlin
package ai.claudeflow.core.{package}

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class {ClassName}Test : DescribeSpec({
    describe("{ClassName}") {
        describe("{method}") {
            it("should {expected behavior}") {
                // Given

                // When

                // Then
            }
        }
    }
})
```

## 테스트 실행

```bash
./gradlew :claude-flow-core:test --tests "*{ClassName}Test"
```

컴포넌트를 분석하고 주요 기능에 대한 테스트를 작성하세요.
