export * from './administracinDeUsuarios.service';
import { AdministracinDeUsuariosService } from './administracinDeUsuarios.service';
export * from './autenticacin.service';
import { AutenticacinService } from './autenticacin.service';
export * from './categoras.service';
import { CategorasService } from './categoras.service';
export * from './cuentas.service';
import { CuentasService } from './cuentas.service';
export const APIS = [AdministracinDeUsuariosService, AutenticacinService, CategorasService, CuentasService];
