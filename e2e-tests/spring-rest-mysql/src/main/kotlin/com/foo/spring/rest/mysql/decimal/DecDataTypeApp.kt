package com.foo.spring.rest.mysql.decimal

import com.foo.spring.rest.mysql.SwaggerConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import springfox.documentation.swagger2.annotations.EnableSwagger2
import javax.persistence.EntityManager

@EnableSwagger2
@SpringBootApplication(exclude = [SecurityAutoConfiguration::class])
@RequestMapping(path = ["/api/decimal"])
open class DecDataTypeApp : SwaggerConfiguration() {

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SpringApplication.run(DecDataTypeApp::class.java, *args)
        }
    }

    @Autowired
    private lateinit var em : EntityManager


    @GetMapping
    open fun get() : ResponseEntity<Any> {

        val pquery = em.createNativeQuery("select * from DECTABLE where pnum < 42.42 and pnum > 42.24 and num > -42.42 and num < -42.24")
        val res = pquery.resultList
        val status = if(res.isEmpty()) 400 else 200

        return ResponseEntity.status(status).build<Any>()
    }
}