package com.spring.simulation;

import com.spring.simulation.ioc.factory.*;
import com.spring.simulation.ioc.context.*;
import com.spring.simulation.ioc.annotation.*;
import com.spring.simulation.ioc.lifecycle.*;
import com.spring.simulation.ioc.postprocessor.AutowiredAnnotationBeanPostProcessor;
import com.spring.simulation.aop.*;
import com.spring.simulation.transaction.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 无第三方依赖的行为回归；失败抛 AssertionError，不能以日志替代断言。 */
public class RegressionTests {
    interface Task { void run() throws Exception; }
    static int passed;
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static void fails(Task task, String message) throws Exception {
        try { task.run(); } catch (Exception expected) {
            check(expected.getMessage() != null && expected.getMessage().contains(message), "Wrong failure: " + expected);
            return;
        }
        throw new AssertionError("Expected failure: " + message);
    }
    static void test(String name, Task task) throws Exception { task.run(); passed++; System.err.println("PASS " + name); }
    static void reg(BeanFactory f, String name, Class<?> type) { f.registerBeanDefinition(name, new BeanDefinition(name, type)); }
    static BeanFactory factory() {
        BeanFactory f = new BeanFactory();
        f.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor(f));
        return f;
    }
    static void aop(BeanFactory f) {
        AopAutoProxyCreator p = new AopAutoProxyCreator(); p.setBeanFactory(f); f.addBeanPostProcessor(p);
    }
    public static class Plain {}
    public interface Api { void run(); }
    public static class A implements Api { @Autowired B b; @Transactional public void run() {} }
    public static class B { @Autowired Api a; }
    public static class C { public C(D d) {} }
    public static class D { public D(C c) {} }
    public interface InnerApi { void run(); }
    public static class Inner implements InnerApi { @Transactional(propagation="MANDATORY") public void run() {} }
    public static class Outer implements Api { @Autowired InnerApi inner; @Transactional public void run() { inner.run(); } }
    public static class Resource implements DisposableBean {
        static int created, destroyed;
        public Resource() { created++; }
        public void destroy() { destroyed++; }
    }
    public static class BadDestroy implements DisposableBean { public void destroy() { throw new IllegalStateException("destroy failure"); } }
    public static class Failing implements InitializingBean { public void afterPropertiesSet() { throw new IllegalStateException("init failure"); } }
    public static class Retry implements Api, InitializingBean {
        static boolean fail = true;
        @Autowired RetryDependent dependent;
        @Transactional public void run() {}
        public void afterPropertiesSet() { if (fail) throw new IllegalStateException("retry failure"); }
    }
    public static class RetryDependent { @Autowired Api retry; }
    public static class Managed implements Api {
        static int cleaned;
        @Transactional public void run() {}
        public void cleanup() { cleaned++; }
    }
    public static class ConcurrentBean {
        static CountDownLatch entered, release;
        static final AtomicInteger count = new AtomicInteger();
        public ConcurrentBean() throws Exception {
            count.incrementAndGet(); entered.countDown();
            check(release.await(5, TimeUnit.SECONDS), "constructor release timed out");
        }
    }
    static final List<String> order = new ArrayList<>();
    public static class Low implements BeanPostProcessor, Ordered {
        public int getOrder() { return 10; }
        public Object postProcessBeforeInitialization(Object b, String n) { if (n.equals("p")) order.add("low"); return b; }
        public Object postProcessAfterInitialization(Object b, String n) { return b; }
    }
    public static class High extends Low {
        public int getOrder() { return -10; }
        public Object postProcessBeforeInitialization(Object b, String n) { if (n.equals("p")) order.add("high"); return b; }
    }
    public static void main(String[] args) throws Exception {
        test("register then refresh / one refresh / close", () -> {
            ApplicationContext c = new ApplicationContext(); c.registerBeanDefinition("p", Plain.class); c.refresh();
            check(c.getBean("p") instanceof Plain, "definition lost");
            fails(c::refresh, "one refresh"); c.close(); c.close(); fails(() -> c.getBean("p"), "closed");
        });
        test("configuration scan and factory methods", () -> {
            AnnotationConfigApplicationContext c = new AnnotationConfigApplicationContext("com.spring.simulation.examples.context");
            c.refresh(); check(c.getBean("dataSource") == c.getBean("dataSource"), "singleton factory method");
            check(c.getBean("connection") != c.getBean("connection"), "prototype factory method"); c.close();
        });
        test("early proxy identity", () -> {
            BeanFactory f = factory(); aop(f); reg(f,"a",A.class); reg(f,"b",B.class);
            Object a = f.getBean("a"); check(java.lang.reflect.Proxy.isProxyClass(a.getClass()), "not proxied");
            check(a == ((B) f.getBean("b")).a, "raw reference escaped"); ((B) f.getBean("b")).a.run();
        });
        test("constructor and prototype cycle rejection", () -> {
            BeanFactory f = factory(); reg(f,"c",C.class); reg(f,"d",D.class);
            fails(() -> f.getBean("c"), "circular dependency");
            f.getBeanDefinition("c").setScope("prototype"); f.getBeanDefinition("d").setScope("prototype");
            fails(() -> f.getBean("c"), "Circular prototype");
        });
        test("concurrent singleton identity", () -> {
            BeanFactory f = factory(); reg(f,"r",ConcurrentBean.class);
            ConcurrentBean.entered = new CountDownLatch(1); ConcurrentBean.release = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Object> first = pool.submit(() -> f.getBean("r"));
                check(ConcurrentBean.entered.await(5,TimeUnit.SECONDS), "constructor not reached");
                CountDownLatch secondStarted = new CountDownLatch(1);
                Future<Object> second = pool.submit(() -> { secondStarted.countDown(); return f.getBean("r"); });
                check(secondStarted.await(5,TimeUnit.SECONDS), "second caller not started");
                ConcurrentBean.release.countDown();
                check(first.get(5,TimeUnit.SECONDS) == second.get(5,TimeUnit.SECONDS), "two singletons");
                check(ConcurrentBean.count.get() == 1, "two constructor calls");
            } finally { ConcurrentBean.release.countDown(); pool.shutdownNow(); }
        });
        test("REQUIRED participants / rollback-only", () -> {
            TransactionManager m = new TransactionManager(); var outer=m.begin(Propagation.REQUIRED); var inner=m.begin(Propagation.REQUIRED);
            check(!inner.isNewTransaction(), "participant owns transaction"); m.commit(inner);
            check(outer.isActive(), "premature commit"); m.commit(outer); check(m.getCurrentTransaction()==null,"leak");
            outer=m.begin(Propagation.REQUIRED); inner=m.begin(Propagation.REQUIRED); m.rollback(inner);
            check(outer.isRollbackOnly(), "missing rollback-only"); var finalOuter=outer;
            fails(() -> m.commit(finalOuter), "rollback-only"); check(m.getCurrentTransaction()==null,"rollback leak");
        });
        test("suspend / resume / nested / nontransactional propagation", () -> {
            TransactionManager m=new TransactionManager(); var outer=m.begin(Propagation.REQUIRED);
            var none=m.begin(Propagation.NOT_SUPPORTED); check(m.getCurrentTransaction()==null,"suspension ineffective");
            var inner=m.begin(Propagation.REQUIRED); check(inner!=outer && inner.isNewTransaction(),"joined suspended transaction");
            m.commit(inner); check(m.getCurrentTransaction()==null,"nontransaction scope lost"); m.commit(none);
            check(m.getCurrentTransaction()==outer && !outer.isSuspended(),"resume failed");
            var fresh=m.begin(Propagation.REQUIRES_NEW); m.rollback(fresh); check(m.getCurrentTransaction()==outer,"new rollback resume");
            var nested=m.begin(Propagation.NESTED); m.rollback(nested); check(outer.isActive()&&!outer.isRollbackOnly(),"nested poisons parent");
            var supports=m.begin(Propagation.SUPPORTS); m.commit(supports); var mandatory=m.begin(Propagation.MANDATORY); m.commit(mandatory);
            fails(() -> m.begin(Propagation.NEVER),"Existing transaction"); m.commit(outer);
            fails(() -> m.begin(Propagation.MANDATORY),"No existing transaction");
            m.commit(m.begin(Propagation.SUPPORTS)); m.commit(m.begin(Propagation.NEVER)); check(m.getCurrentTransaction()==null,"leak");
        });
        test("cross bean transaction manager", () -> {
            BeanFactory f=factory(); aop(f); reg(f,"outer",Outer.class); reg(f,"inner",Inner.class);
            ((Api)f.getBean("outer")).run(); check(f.getTransactionManager().getCurrentTransaction()==null,"cross-bean leak");
        });
        test("refresh failure cleanup and state", () -> {
            Resource.destroyed=0;
            ApplicationContext c=new ApplicationContext(){protected void loadBeanDefinitions(){registerBeanDefinition("r",Resource.class);registerBeanDefinition("f",Failing.class);}};
            fails(c::refresh,"init failure"); check(Resource.destroyed==1,"resource leaked"); fails(() -> c.getBean("r"),"refresh failed");
        });
        test("destroy failure continues and clears caches", () -> {
            Resource.destroyed=0; BeanFactory f=factory(); reg(f,"r",Resource.class); reg(f,"bad",BadDestroy.class);
            Object old=f.getBean("r"); f.getBean("bad"); fails(f::destroySingletons,"destroy failure");
            check(Resource.destroyed==1,"remaining bean skipped"); check(f.getBean("r")!=old,"cache not cleared");
        });
        test("failure retry clears early proxies and dependent singletons", () -> {
            BeanFactory f=factory(); aop(f); reg(f,"retry",Retry.class); reg(f,"dependent",RetryDependent.class);
            fails(() -> f.getBean("retry"),"retry failure"); Retry.fail=false;
            Object retry=f.getBean("retry"); check(((RetryDependent)f.getBean("dependent")).retry==retry,"stale early reference after retry");
        });
        test("destroy callback uses target behind proxy", () -> {
            BeanFactory f=factory(); aop(f); BeanDefinition d=new BeanDefinition("m",Managed.class); d.setDestroyMethodName("cleanup");f.registerBeanDefinition("m",d);
            f.getBean("m");f.destroySingletons(); check(Managed.cleaned==1,"target cleanup missing");
        });
        test("BFPP definitions participate in startup and BPP discovery", () -> {
            Resource.created=0; order.clear(); ApplicationContext c=new ApplicationContext();
            c.addBeanFactoryPostProcessor(f->{reg(f,"r",Resource.class);reg(f,"low",Low.class);reg(f,"high",High.class);reg(f,"p",Plain.class);});
            c.refresh();check(Resource.created==1,"BFPP definition not eager");check(order.equals(List.of("high","low")),"BPP missing or unsorted: "+order);c.close();
        });
        test("manual singleton dependency resolution", () -> {
            BeanFactory f=factory(); Plain p=new Plain();f.registerSingleton("p",p);
            check(f.getBeanByType(Plain.class)==p,"type lookup");check(f.resolveDependency(Plain.class,"p")==p,"qualifier lookup");
        });
        System.err.println("Regression tests passed: " + passed);
    }
}
