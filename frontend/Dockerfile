FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY index.html /usr/share/nginx/html/
COPY css/       /usr/share/nginx/html/css/
COPY js/        /usr/share/nginx/html/js/

# Automatically append a build timestamp to all script imports to bust the browser cache
RUN find /usr/share/nginx/html -type f \( -name "*.html" -o -name "*.js" \) -exec sed -i "s/?v=[0-9]*/?v=$(date +%s)/g" {} +

EXPOSE 80
