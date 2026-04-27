package com.ll.framework.ioc;

import com.ll.framework.ioc.annotations.Bean;
import com.ll.framework.ioc.annotations.Configuration;
import com.ll.framework.ioc.annotations.Repository;
import com.ll.framework.ioc.annotations.Service;
import com.ll.standard.util.Ut;
import org.reflections.Reflections;

import java.lang.reflect.Method;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class ApplicationContext {
    // 프로젝트 전체를 스캔해서 클래스 정보를 긁어올 탐지기
    private final Reflections reflections;

    // 일반 클래스 빈의 설계도(Class)를 저장 (이름 -> 클래스 객체)
    private final Map<String, Class<?>> beanDefinitions = new HashMap<>();

    // 실제로 생성된 객체(Bean)를 저장 (이름 -> 인스턴스) -> 싱글톤 보장용
    private final Map<String, Object> beans = new HashMap<>();

    // @Bean 메서드 그 자체를 저장 (이름 -> Method 객체)
    private final Map<String, Method> beanMethods = new HashMap<>();

    // @Bean 메서드가 어느 Configuration 클래스에 속해있는지 저장 (이름 -> Configuration 클래스)
    private final Map<String, Class<?>> beanMethodOwners = new HashMap<>();

    // DI(의존성 주입)를 위해 '타입'으로 '빈 이름'을 찾기 위한 전화번호부
    private final Map<Class<?>, String> typeToBeanName = new HashMap<>();

    public ApplicationContext(String basePackage) {
        this.reflections = new Reflections(basePackage);
    }

    public void init() {
        // 1. 모든 컴포넌트 클래스 스캔 (Service, Repository, Configuration)
        Set<Class<?>> componentClasses = reflections.getTypesAnnotatedWith(Service.class);
        componentClasses.addAll(reflections.getTypesAnnotatedWith(Repository.class));
        componentClasses.addAll(reflections.getTypesAnnotatedWith(Configuration.class));

        for (Class<?> clazz : componentClasses) {
            // 2. 일반 클래스들이라면 바로 빈으로 만듬
            getClassBean(clazz);

            // 3. 만약 @Configuration 클래스라면, 그 내부의 @Bean 메서드들도 스캔
            if (clazz.isAnnotationPresent(Configuration.class)) {
                getMethodBeanFromConfiguration(clazz);
            }
        }
    }

    public <T> T genBean(String beanName) {
        // 싱글톤 캐시 확인: 이미 만들어진 놈 있으면 걔를 반환
        if (beans.containsKey(beanName)) {
            return (T) beans.get(beanName);
        }

        // CASE 1: 그냥 클래스 빈일 때
        if (beanDefinitions.containsKey(beanName)) {
            return genClassBean(beanName);
        }

        // CASE 2: @Bean 메서드에서 생성되는 빈일 때
        if (beanMethods.containsKey(beanName)) {
            return genMethodBean(beanName);
        }

        return null;
    }

    private void getClassBean(Class<?> clazz) {
        // 클래스 이름으로 빈 이름 생성 (ex: TestPostService -> testPostService)
        String beanName = Ut.str.lcfirst(clazz.getSimpleName());

        // 설계도 저장 및 타입 등록
        beanDefinitions.put(beanName, clazz);
        typeToBeanName.put(clazz, beanName);
    }

    private void getMethodBeanFromConfiguration(Class<?> clazz) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Bean.class)) {
                // 메서드 이름이 곧 빈 이름
                String beanNameFromMethod = method.getName();

                // 메서드 정보와 그 메서드를 가진 클래스(owner) 저장
                beanMethods.put(beanNameFromMethod, method);
                beanMethodOwners.put(beanNameFromMethod, clazz);

                // 리턴 타입으로도 찾을 수 있게 등록 (DI 핵심)
                typeToBeanName.put(method.getReturnType(), beanNameFromMethod);
            }
        }
    }


    // [클래스 생성 전략] 생성자를 통한 인스턴스화
    private <T> T genClassBean(String beanName) {
        try {
            Class<?> clazz = beanDefinitions.get(beanName);
            // 생성자 하나만 있다고 가정하고 가져옴
            Constructor<?> constructor = clazz.getDeclaredConstructors()[0];

            // 생성자에 필요한 인자들 재귀적으로 조립
            Object[] args = resolveArgs(constructor.getParameterTypes());

            T instance = (T) constructor.newInstance(args); // 실제 생성
            beans.put(beanName, instance); // 생성한 객체 저장 (캐싱)
            return instance;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    // [메서드 실행 전략] @Bean 메서드 실행을 통한 인스턴스화
    private <T> T genMethodBean(String beanName) {
        try {
            Method method = beanMethods.get(beanName);
            Class<?> ownerClazz = beanMethodOwners.get(beanName);

            // @Bean 메서드는 Configuration 클래스 인스턴스가 있어야 호출 가능하므로,
            // 이 설정 클래스 자체를 먼저 빈으로 생성함 (재귀 호출)
            Object ownerInstance = genBean(Ut.str.lcfirst(ownerClazz.getSimpleName()));

            // 메서드 인자 의존성 조립
            Object[] args = resolveArgs(method.getParameterTypes());

            // Invoke(동사) 법/규칙을 적용/들먹이다, (권리/도움 등을) 호소/불러내다,
            // (IT) 함수나 프로그램을 호출하다라는 뜻
            // 메서드 실행 (invoke)해서 결과물(Bean) 반환
            T instance = (T) method.invoke(ownerInstance, args);
            beans.put(beanName, instance); // 생성한 객체 저장
            return instance;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // 의존성 주입의 핵심 : 타입 정보를 가지고 빈을 재귀적으로 다 찾아옴
    private Object[] resolveArgs(Class<?>[] parameterTypes) {
        Object[] args = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            // (typeToBeanName)에서 타입에 맞는 빈 이름 획득
            String paramBeanName = typeToBeanName.get(parameterTypes[i]);

            // 못 찾았으면 이름 기반(lcfirst)으로 fallback
            if (paramBeanName == null) {
                paramBeanName = Ut.str.lcfirst(parameterTypes[i].getSimpleName());
            }

            // 찾아낸 빈 이름으로 다시 genBean 호출 (의존성 체인 조립)
            args[i] = genBean(paramBeanName);
        }
        return args;
    }
}