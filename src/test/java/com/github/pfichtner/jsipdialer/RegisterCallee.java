package com.github.pfichtner.jsipdialer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(RegisteredCalleeExtension.class)
public @interface RegisterCallee {
	boolean awaitRegistration() default true;

	String user() default "callee";
}
