package dh.timetriggeredkstreams

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class TimeTriggeredKstreamsApplication

fun main(args: Array<String>) {
    runApplication<TimeTriggeredKstreamsApplication>(*args)
}
