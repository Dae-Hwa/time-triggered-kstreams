package dh.timetriggeredkstreams

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
    fromApplication<TimeTriggeredKstreamsApplication>().with(TestcontainersConfiguration::class).run(*args)
}
