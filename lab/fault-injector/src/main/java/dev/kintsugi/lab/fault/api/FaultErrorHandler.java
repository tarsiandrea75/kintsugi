package dev.kintsugi.lab.fault.api;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import dev.kintsugi.lab.fault.api.FaultResponses.Error;
import dev.kintsugi.lab.fault.application.ActivationFailedException;
import dev.kintsugi.lab.fault.application.FaultAlreadyActiveException;
import dev.kintsugi.lab.fault.application.InjectionNotFoundException;
import dev.kintsugi.lab.fault.application.RestoreFailedException;
import dev.kintsugi.lab.fault.application.TruthUnavailableException;
import dev.kintsugi.lab.fault.application.UnknownFaultException;
import dev.kintsugi.lab.fault.catalog.ParameterRefusedException;

/**
 * Maps failures to fixed error bodies. No body echoes an exception message or the request: a lab failure message can
 * name the target service, which is part of the true cause.
 */
@RestControllerAdvice
class FaultErrorHandler {

	/** 400 reason for a parameter name the fault does not declare. Names no parameter, so it reveals no target. */
	static final String UNDECLARED_PARAMETER = "undeclared parameter: this fault takes no parameter of that name";

	/** 400 reason for a declared parameter with a value outside its declared values. Lists none of them. */
	static final String UNDECLARED_VALUE = "undeclared value: the value is not one of the fault's declared values";

	@ExceptionHandler({ UnknownFaultException.class, HttpMessageNotReadableException.class })
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	Error badRequest() {
		return new Error("unknown fault: the body must be {\"fault\": \"<catalog id>\"}");
	}

	@ExceptionHandler(ParameterRefusedException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	Error parameterRefused(ParameterRefusedException refused) {
		return new Error(switch (refused.reason()) {
			case UNDECLARED_PARAMETER -> UNDECLARED_PARAMETER;
			case UNDECLARED_VALUE -> UNDECLARED_VALUE;
		});
	}

	@ExceptionHandler({ InjectionNotFoundException.class, MethodArgumentTypeMismatchException.class })
	@ResponseStatus(HttpStatus.NOT_FOUND)
	Error notFound() {
		return new Error("injection not found");
	}

	@ExceptionHandler(FaultAlreadyActiveException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	Error conflict() {
		return new Error("a fault is already active; restore it first");
	}

	@ExceptionHandler(ActivationFailedException.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	Error activationFailed(ActivationFailedException failure) {
		return new Error("activation failed", failure.id());
	}

	@ExceptionHandler(RestoreFailedException.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	Error restoreFailed(RestoreFailedException failure) {
		return new Error("restore failed", failure.id());
	}

	@ExceptionHandler({ TruthUnavailableException.class, DataAccessException.class, TransactionException.class })
	@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
	Error databaseUnavailable() {
		return new Error("the faults database is unreachable");
	}

}
