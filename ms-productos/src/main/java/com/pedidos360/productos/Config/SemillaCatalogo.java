package com.pedidos360.productos.Config;

import java.time.Instant;
import java.util.List;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.pedidos360.productos.Model.Categoria;
import com.pedidos360.productos.Model.Producto;
import com.pedidos360.productos.Repository.CategoriaRepositorio;
import com.pedidos360.productos.Repository.ProductoRepositorio;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Catálogo semilla de §7.1: la fuente de verdad única que también usan
 * ms-recomendaciones (respaldo) y la tienda.
 *
 * Sólo se inserta si la tabla está vacía: reiniciar el servicio no debe pisar el stock
 * real que ya cambió con reservas y reposiciones.
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class SemillaCatalogo implements CommandLineRunner {

    private final ProductoRepositorio productos;
    private final CategoriaRepositorio categorias;

    @Override
    public void run(String... args) {
        cargarSiVacio();
    }

    /** Cada saveAll ya corre en su propia transacción (SimpleJpaRepository): no hace falta una externa. */
    public void cargarSiVacio() {
        if (categorias.count() == 0) {
            List<Categoria> semilla = categoriasSemilla();
            categorias.saveAll(semilla);
            log.info("Semilla: {} categorías insertadas", semilla.size());
        }
        if (productos.count() == 0) {
            Instant ahora = Instant.now();
            List<Producto> semilla = productosSemilla(ahora);
            productos.saveAll(semilla);
            log.info("Semilla: {} productos insertados en productos_db", semilla.size());
        } else {
            log.info("Semilla omitida: la tabla producto ya tiene datos");
        }
    }

    /** Orden de la lista = orden en que la tienda muestra las categorías. */
    public static List<Categoria> categoriasSemilla() {
        return List.of(
            new Categoria("celulares", "Celulares", 1),
            new Categoria("laptops", "Laptops", 2),
            new Categoria("audio", "Audio", 3),
            new Categoria("gaming", "Gaming", 4),
            new Categoria("tv-video", "TV y video", 5),
            new Categoria("smart-home", "Smart home", 6),
            new Categoria("accesorios", "Accesorios", 7),
            new Categoria("camaras", "Cámaras", 8));
    }

    public static List<Producto> productosSemilla(Instant ahora) {
        // imagenUrl apunta a /img/<id>.svg, servido por el nginx de la tienda desde public/img.
        // Ruta relativa a proposito: no depende de la IP publica, que cambia en cada arranque.
        return List.of(
                p("item_101", "Galaxy S24", "Samsung", "celulares", "gama-alta", 649990, 14, 4.7, "6.2\" AMOLED, 256 GB.", ahora),
                p("item_102", "iPhone 15", "Apple", "celulares", "gama-alta", 799990, 6, 4.8, "Chip A16, cámara de 48 MP.", ahora),
                p("item_103", "Moto G84", "Motorola", "celulares", "gama-media", 229990, 40, 4.4, "Pantalla pOLED, 5G.", ahora),
                p("item_104", "ZenBook 14", "ASUS", "laptops", "ultraliviana", 749990, 22, 4.6, "Ryzen 7, 16 GB, SSD 512 GB.", ahora),
                p("item_105", "MacBook Air M2", "Apple", "laptops", "ultraliviana", 1099990, 9, 4.9, "13.6\", 8 GB, 256 GB.", ahora),
                p("item_106", "IdeaPad Slim 3", "Lenovo", "laptops", "uso-diario", 429990, 33, 4.3, "Core i5, 8 GB, SSD 512 GB.", ahora),
                p("item_107", "WH-1000XM5", "Sony", "audio", "audifonos", 299990, 18, 4.8, "Cancelación de ruido activa.", ahora),
                p("item_108", "AirPods Pro 2", "Apple", "audio", "audifonos", 249990, 12, 4.7, "Audio espacial, estuche USB-C.", ahora),
                p("item_109", "Flip 6", "JBL", "audio", "parlantes", 119990, 52, 4.6, "Parlante portátil IP67.", ahora),
                p("item_110", "PlayStation 5", "Sony", "gaming", "consolas", 549990, 5, 4.9, "Consola con lector de discos.", ahora),
                p("item_111", "Switch OLED", "Nintendo", "gaming", "consolas", 329990, 27, 4.8, "Pantalla OLED de 7\".", ahora),
                p("item_112", "DualSense", "Sony", "gaming", "controles", 74990, 70, 4.6, "Control inalámbrico háptico.", ahora),
                p("item_113", "Bravia 55\" 4K", "Sony", "tv-video", "televisores", 699990, 10, 4.6, "Google TV, HDR10, 120 Hz.", ahora),
                p("item_114", "Fire TV Stick 4K", "Amazon", "tv-video", "streaming", 59990, 45, 4.4, "Streaming 4K con Alexa.", ahora),
                p("item_115", "Echo Dot 5", "Amazon", "smart-home", "asistentes", 54990, 38, 4.5, "Parlante inteligente con Alexa.", ahora),
                p("item_116", "Hue White & Color", "Philips", "smart-home", "iluminacion", 39990, 60, 4.4, "Ampolleta inteligente E27.", ahora),
                p("item_117", "Cargador GaN 65 W", "Anker", "accesorios", "carga", 34990, 80, 4.7, "USB-C, carga rápida para laptop.", ahora),
                p("item_118", "Funda MagSafe iPhone 15", "Apple", "accesorios", "fundas", 49990, 35, 4.3, "Silicona con imanes MagSafe.", ahora),
                p("item_119", "Alpha a6400", "Sony", "camaras", "mirrorless", 899990, 7, 4.8, "24 MP, enfoque en tiempo real.", ahora),
                p("item_120", "HERO12 Black", "GoPro", "camaras", "accion", 429990, 15, 4.7, "5.3K, sumergible 10 m.", ahora));
    }

    private static Producto p(String id, String nombre, String marca, String categoria, String subcategoria,
                              long precio, int stock, double calificacion, String descripcion, Instant ahora) {
        return Producto.builder()
                .id(id).nombre(nombre).marca(marca).categoria(categoria).subcategoria(subcategoria)
                .precio(precio).stock(stock).calificacion(calificacion).imagenUrl("/img/" + id + ".svg")
                .descripcion(descripcion).actualizadoEn(ahora)
                .build();
    }
}
