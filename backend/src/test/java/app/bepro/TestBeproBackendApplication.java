package app.bepro;

import org.springframework.boot.SpringApplication;

public class TestBeproBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(BeproBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
