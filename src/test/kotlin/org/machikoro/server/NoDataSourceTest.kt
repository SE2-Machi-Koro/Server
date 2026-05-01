package org.machikoro.server

import org.springframework.boot.test.context.SpringBootTest

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(
    properties = ["machikoro.db.init.enabled=false"]
)
annotation class SpringBootTestWithoutDataSource