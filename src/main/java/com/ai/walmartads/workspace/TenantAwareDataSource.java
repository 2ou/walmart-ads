package com.ai.walmartads.workspace;

import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

final class TenantAwareDataSource extends AbstractDataSource {

    private final DataSource delegate;
    private final WorkspaceSqlGuard guard;

    TenantAwareDataSource(DataSource delegate, WorkspaceSqlGuard guard) {
        this.delegate = delegate;
        this.guard = guard;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return connectionProxy(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return connectionProxy(delegate.getConnection(username, password));
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }

    private Connection connectionProxy(Connection connection) {
        InvocationHandler handler = (proxy, method, args) -> {
            try {
                if (args != null && args.length > 0 && args[0] instanceof String sql
                        && (method.getName().startsWith("prepare") || method.getName().equals("nativeSQL"))) {
                    args[0] = guard.rewrite(sql);
                }
                Object result = method.invoke(connection, args);
                if (result instanceof Statement statement && method.getName().equals("createStatement")) {
                    return statementProxy(statement);
                }
                return result;
            } catch (InvocationTargetException exception) {
                throw exception.getTargetException();
            }
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, handler);
    }

    private Statement statementProxy(Statement statement) {
        InvocationHandler handler = (proxy, method, args) -> {
            try {
                if (args != null && args.length > 0 && args[0] instanceof String sql
                        && (method.getName().startsWith("execute") || method.getName().equals("addBatch"))) {
                    args[0] = guard.rewrite(sql);
                }
                return method.invoke(statement, args);
            } catch (InvocationTargetException exception) {
                throw exception.getTargetException();
            }
        };
        return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(),
                new Class<?>[]{Statement.class}, handler);
    }
}
