package com.foo.rest.examples.spring.openapi.v3.wiremock.auth0

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration

@SpringBootApplication(exclude = [SecurityAutoConfiguration::class])
open class WmAuth0Application {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SpringApplication.run(WmAuth0Application::class.java, *args)
        }
    }
}