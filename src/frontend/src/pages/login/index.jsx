import { useForm } from "react-hook-form";
import { z } from "zod";
import { zodResolver } from "@hookform/resolvers/zod";
import request from "@/services/request";

// 1. 定义校验模式 (Schema)
const loginSchema = z.object({
    username: z.string()
        .min(1, '用户名不能为空')
        .min(3, '用户名至少需要3个字符'),
    password: z.string()
        .min(1, '密码不能为空')
        .min(6, '密码长度不能少于6位'),
});

export default function Login() {
    // 2. 初始化 React Hook Form
    const {
        register,
        handleSubmit,
        setError, // 用于手动设置错误
        formState: { errors, isSubmitting },
    } = useForm({
        resolver: zodResolver(loginSchema), // 关键：将 Zod 接入 Hook Form
        defaultValues: {
            username: '',
            password: '',
        },
    });

    // 3. 提交处理
    const onSubmit = async (data) => {
        // 只有当验证通过时，这个函数才会被触发
        console.log('提交的数据:', data);

        // 模拟 API 请求
        await new Promise((resolve) => setTimeout(resolve, 2000));
        alert('登录成功！');

        /*
        try {
          // Axios 会根据状态码自动判断胜负
          const response = await request.post('/api/login', data);
          // 如果成功 (2xx)
          console.log('登录成功:', response.data);
          // 这里可以执行跳转或存储 Token 逻辑
        } catch (error) {
          // Axios 的错误对象包含 response
          if (error.response) {
            // 请求已发出，服务器响应了状态码（如 400, 401, 500）
            const status = error.response.status;
            const message = error.response.data?.message || '登录失败';

            if (status === 400 || status === 401) {
              // 将错误精准地关联到字段上
              setError('username', { type: 'manual', message: message });
              setError('password', { type: 'manual', message: '请检查您的凭据' });
            } else {
              // 其他服务器错误
              setError('root.serverError', { type: 'manual', message: '服务器繁忙，请稍后再试' });
            }
          } else if (error.request) {
            // 请求已发出，但没有收到响应（网络断开或超时）
            setError('root.serverError', { type: 'manual', message: '无法连接到服务器，请检查网络' });
          } else {
            // 发生了一些设置请求时的意外错误
            console.error('Error', error.message);
          }
        }
        */
    };

    return (
        <div style={{ maxWidth: '300px', margin: '50px auto' }}>
            <h2>用户登录</h2>
            <form onSubmit={handleSubmit(onSubmit)}>
                {/* 用户名字段 */}
                <div style={{ marginBottom: '15px' }}>
                    <label htmlFor="login_username">用户名:</label>
                    <input {...register('username')} id="login_username" style={{ display: 'block', width: '100%' }} />
                    {errors.username && (<span style={{ color: 'red', fontSize: '12px' }}>{errors.username.message}</span>)}
                </div>

                {/* 密码字段 */}
                <div style={{ marginBottom: '15px' }}>
                    <label htmlFor="login_pwd">密码:</label>
                    <input type="password" {...register('password')} id="login_pwd" style={{ display: 'block', width: '100%' }} />
                    {errors.password && (<span style={{ color: 'red', fontSize: '12px' }}>{errors.password.message}</span>)}
                </div>

                {/* 处理根级错误（如服务器挂了） */}
                {errors.root?.serverError && (<p style={{ color: 'red' }}>{errors.root.serverError.message}</p>)}

                <button type="submit" disabled={isSubmitting}>
                    {isSubmitting ? '登录中...' : '登录'}
                </button>
            </form>
        </div>
    );
};